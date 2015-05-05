/*
 * The MIT License
 *
 * Copyright (c) 2011, Nathan Parry
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.slaves;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.PingThread;
import jenkins.security.MasterToSlaveCallable;
import jenkins.slaves.PingFailureAnalyzer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static java.util.logging.Level.*;

/**
 * Establish a periodic ping to keep connections between {@link Slave slaves}
 * and the main Jenkins node alive. This prevents network proxies from
 * terminating connections that are idle for too long.
 *
 * @since 1.405
 */
@Extension
public class ChannelPinger extends ComputerListener {
    private static final Logger LOGGER = Logger.getLogger(ChannelPinger.class.getName());
    private static final String TIMEOUT_SECONDS_PROPERTY = ChannelPinger.class.getName() + ".pingTimeoutSeconds";
    private static final String INTERVAL_MINUTES_PROPERTY = ChannelPinger.class.getName() + ".pingInterval";
    private static final String INTERVAL_SECONDS_PROPERTY = ChannelPinger.class.getName() + ".pingIntervalSeconds";

    private static final int DEFAULT_TIMEOUT_SECONDS = Integer.getInteger(TIMEOUT_SECONDS_PROPERTY, 4 * 60);
    private static final int DEFAULT_INTERVAL_SECONDS = Integer.getInteger(INTERVAL_SECONDS_PROPERTY, 60 * Integer.getInteger(INTERVAL_MINUTES_PROPERTY, 5));

    /**
     * Timeout for the ping in seconds.
     */
    private final int pingTimeoutSeconds;

    /**
     * Interval for the ping in seconds.
     */
    private final int pingIntervalSeconds;


    public ChannelPinger() {
        this(DEFAULT_TIMEOUT_SECONDS, DEFAULT_INTERVAL_SECONDS);
    }

    public ChannelPinger(int pingTimeoutSeconds, int pingIntervalSeconds) {
        this.pingTimeoutSeconds = pingTimeoutSeconds;
        this.pingIntervalSeconds = pingIntervalSeconds;

        if (pingIntervalSeconds < pingTimeoutSeconds) {
            LOGGER.warning("Ping interval (" + pingIntervalSeconds + ") is less than ping timeout (" + pingTimeoutSeconds + ")");
        }
    }

    @Override
    public void preOnline(Computer c, Channel channel, FilePath root, TaskListener listener)  {
        install(channel);
    }

    public void install(Channel channel) {
        if (pingTimeoutSeconds < 1 || pingIntervalSeconds < 1) {
            LOGGER.fine("Slave ping is disabled");
            return;
        }

        try {
            channel.call(new SetUpRemotePing(pingTimeoutSeconds, pingIntervalSeconds));
            LOGGER.fine("Set up a remote ping for " + channel.getName());
        } catch (Exception e) {
            LOGGER.severe("Failed to set up a ping for " + channel.getName());
        }

        // set up ping from both directions, so that in case of a router dropping a connection,
        // both sides can notice it and take compensation actions.
        setUpPingForChannel(channel, pingTimeoutSeconds, pingIntervalSeconds, "master");
    }

    private static class SetUpRemotePing extends MasterToSlaveCallable<Void, IOException> {
        private final int pingTimeoutSeconds;
        private final int pingIntervalSeconds;
        public SetUpRemotePing(int pingTimeoutSeconds, int pingIntervalSeconds) {
            this.pingTimeoutSeconds = pingTimeoutSeconds;
            this.pingIntervalSeconds = pingIntervalSeconds;
        }

        public Void call() throws IOException {
            setUpPingForChannel(Channel.current(), pingTimeoutSeconds, pingIntervalSeconds, "slave");
            return null;
        }
    }

    private static void setUpPingForChannel(final Channel channel, int timeoutSeconds, int intervalSeconds, final String sender) {
        final AtomicBoolean isInClosed = new AtomicBoolean(false);
        final PingThread t = new PingThread(channel, timeoutSeconds * 1000L, intervalSeconds * 1000L) {
            protected void onDead(Throwable cause) {
                try {
                    for (PingFailureAnalyzer pfa : PingFailureAnalyzer.all()) {
                        pfa.onPingFailure(channel,cause);
                    }
                    if (isInClosed.get()) {
                        LOGGER.log(FINE, "Ping failed from " + sender + " after the channel " + channel.getName() + " is already partially closed.", cause);
                    } else {
                        LOGGER.log(INFO, "Ping failed from " + sender + ". Terminating the channel " + channel.getName() + ".", cause);
                        channel.close(cause);
                    }
                } catch (IOException e) {
                    LOGGER.log(SEVERE, "Failed to terminate the channel " + channel.getName() + " from " + sender, e);
                }
            }
            protected void onDead() {
                onDead(null);
            }
        };

        channel.addListener(new Channel.Listener() {
            @Override
            public void onClosed(Channel channel, IOException cause) {
                LOGGER.fine("Terminating ping thread for " + channel.getName() + " on " + sender);
                isInClosed.set(true);
                t.interrupt();  // make sure the ping thread is terminated
            }
        });

        t.start();
        LOGGER.fine("Ping thread started on " + sender + " for " + channel + " with a " +
            intervalSeconds + " second interval and a " + timeoutSeconds + " second timeout.");
    }
}
