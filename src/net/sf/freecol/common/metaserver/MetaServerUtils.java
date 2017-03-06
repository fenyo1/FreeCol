/**
 *  Copyright (C) 2002-2017   The FreeCol Team
 *
 *  This file is part of FreeCol.
 *
 *  FreeCol is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  FreeCol is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with FreeCol.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.sf.freecol.common.metaserver;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.freecol.FreeCol;
import net.sf.freecol.common.FreeColException;
import net.sf.freecol.common.networking.Connection;
import net.sf.freecol.common.networking.DOMMessage;
import net.sf.freecol.common.networking.DOMMessageHandler;
import net.sf.freecol.common.networking.RegisterServerMessage;
import net.sf.freecol.common.networking.RemoveServerMessage;
import net.sf.freecol.common.networking.ServerListMessage;
import net.sf.freecol.common.networking.TrivialMessage;
import net.sf.freecol.common.networking.UpdateServerMessage;
import static net.sf.freecol.common.util.CollectionUtils.*;
import net.sf.freecol.server.FreeColServer;

import org.w3c.dom.Element;


/**
 * Wrapper class to contain utility functions used by the FreeColServer to
 * talk to the meta-server.
 */
public class MetaServerUtils {

    private static final Logger logger = Logger.getLogger(MetaServerUtils.class.getName());

    /**
     * Handle messages sent by the meta-server.
     * Currently, only "serverList".
     */
    public static class MetaInputHandler implements DOMMessageHandler {

        private static final Logger logger = Logger.getLogger(MetaInputHandler.class.getName());
        
        /** Callback to swallow server lists. */
        public final Consumer<List<ServerInfo>> consumer;


        /**
         * Create a new MetaInputHandler with a given consumer.
         *
         * @param consumer The {@code Consumer} to swallow the server list.
         */
        public MetaInputHandler(Consumer<List<ServerInfo>> consumer) {
            this.consumer = consumer;
        }

        /**
         * {@inheritDoc}
         */
        public Element handle(Connection connection, Element element)
            throws FreeColException {
            if (element == null) return null;
            final String tag = element.getTagName();
            switch (tag) {
            case TrivialMessage.DISCONNECT_TAG:
                disconnect();
                break;
            case ServerListMessage.TAG:
                serverList(new ServerListMessage(element));
                break;
            default:
                logger.warning("MetaInputHandler does not handle: " + tag);
                break;
            }
            return null;
        }

        /**
         * Handle a "disconnect"-message.
         */
        public void disconnect() {} // Do nothing
        
        /**
         * Handle a "serverList"-message.
         */
        public void serverList(ServerListMessage message) {
            this.consumer.accept(message.getServers());
        }            
    }

    /** Client timer update interval. */
    private static final int UPDATE_INTERVAL = 60000;

    /** Sentinel server info to allow check for activity. */
    private static final ServerInfo sentinel = new ServerInfo(null,
        null, -1, -1, -1, false, null, -1);

    /** Type of message to send. */
    private static enum MetaMessageType {
        REGISTER,
        REMOVE,
        SERVERLIST,
        UPDATE,
    };
    
    private static Map<Timer, ServerInfo> updaters
        = Collections.synchronizedMap(new HashMap<>());


    // Internal utilities

    /**
     * Find a timer for the given server.
     *
     * @param si The new {@code ServerInfo} to look for.
     * @return The {@code Timer} found if any.
     */
    private static Timer findTimer(final ServerInfo si) {
        Entry<Timer, ServerInfo> entry = find(updaters.entrySet(),
            matchKeyEquals(si.getName(),
                (Entry<Timer,ServerInfo> e) -> e.getValue().getName()));
        return (entry == null) ? null : entry.getKey();
    }

    /**
     * Utility to start an update timer for a given server.
     *
     * @param si The new {@code ServerInfo} to update with.
     * @return True if the timer was started.
     */
    private static boolean startTimer(final ServerInfo si) {
        // This update is really a "Hi! I am still here!"-message,
        // since an additional update should be sent when a new
        // player is added to/removed from this server etc.
        Timer t = new Timer(true);
        if (t == null) return false;
        updaters.put(t, si);
        t.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    ServerInfo si = updaters.get(this);
                    if (si == null || !updateServer(si)) cancel();
                }
            }, UPDATE_INTERVAL, UPDATE_INTERVAL);
        return true;
    }

    /**
     * Update a currently running timer with new server info.
     *
     * @param si The new {@code ServerInfo} to update with.
     * @return True if the timer was updated.
     */
    private static boolean updateTimer(final ServerInfo si) {
        Timer t = findTimer(si);
        if (t == null) return false;
        updaters.put(t, si);
        return true;
    }

    /**
     * Stop a currently running timer.
     *
     * @param si The new {@code ServerInfo} to update with.
     * @return True if the timer was remove.
     */
    private static boolean stopTimer(final ServerInfo si) {
        Timer t = findTimer(si);
        if (t == null) return false;
        t.cancel();
        updaters.remove(t);
        return true;
    }

    /**
     * Utility to get a connection to the meta-server and handle the
     * server list it returns.
     *
     * @param lsi A list of {@code ServerInfo} records, to be filled in
     *     by the meta-server.     
     * @return A {@code Connection}, or null on failure.
     */
    private static Connection getMetaServerConnection(List<ServerInfo> lsi) {
        // Create a consumer for the response to meta-server
        // "serverList" messages.  Most of the time we do not care, so
        // the default is to do nothing, however if we have a non-null
        // server info list, arrange to fill it.
        Consumer<List<ServerInfo>> consumer = (List<ServerInfo> l) -> {};
        if (lsi != null) {
            lsi.clear();
            lsi.add(sentinel);
            consumer = (l) -> {
                lsi.clear();
                if (lsi != null) lsi.addAll(l);
            };
        }

        String host = FreeCol.getMetaServerAddress();
        int port = FreeCol.getMetaServerPort();
        try {
            return new Connection(host, port, new MetaInputHandler(consumer),
                                  FreeCol.SERVER_THREAD);
        } catch (IOException ioe) {
            logger.log(Level.WARNING, "Could not connect to meta-server: "
                + host + ":" + port, ioe);
        }
        return null;
    }

    /**
     * Send a message to the meta-server.
     *
     * @param type The {@code MetaMessageType} to send.
     * @param si The associated {@code ServerInfo}.
     * @param lsi A list of {@code ServerInfo} records, to be filled
     *     in by the meta-server.
     * @return True if the operation succeeds.
     */
    private static boolean metaMessage(MetaMessageType type, ServerInfo si,
                                       List<ServerInfo> lsi) {
        Connection mc = getMetaServerConnection(lsi);
        if (mc == null) return false;
        si.setConnection(mc.getName(), mc.getHostAddress(), mc.getPort());

        try {
            switch (type) {
            case REGISTER:
                mc.sendAndWait(new RegisterServerMessage(si));
                return startTimer(si);
            case REMOVE:
                mc.sendAndWait(new RemoveServerMessage(si));
                return stopTimer(si);
            case SERVERLIST:
                mc.sendAndWait(new ServerListMessage());
                break; 
            case UPDATE:
                mc.sendAndWait(new UpdateServerMessage(si));
                return updateTimer(si);
            }
        } catch (IOException ioe) {
            logger.log(Level.WARNING, "Meta-server " + type.toString()
                + " failed: " + FreeCol.getMetaServerAddress()
                + ":" + FreeCol.getMetaServerPort(), ioe);
            return false;
        } finally {
            mc.close();
        }
        return true;
    }


    // Public interface

    /**
     * Gets a list of servers from the meta server.
     *
     * @return A list of {@link ServerInfo} objects, or null on error.
     */
    public static List<ServerInfo> getServerList() {
        List<ServerInfo> lsi = new ArrayList<>();
        return (metaMessage(MetaMessageType.SERVERLIST, null, lsi)
            && !(lsi.size() == 1 && lsi.get(0) == sentinel)) ? lsi
            : null;
    }

    /**
     * Register a public server.
     *
     * If successful, an update timer will be returned, which will
     * continually send update messages to the meta-server until cancelled.
     *
     * @return True if the server was registered.
     */
    public static boolean registerServer(ServerInfo si) {
        return metaMessage(MetaMessageType.REGISTER, si, null);
    }

    /**
     * Remove a public server.
     *
     * @return True if the server was removed.
     */
    public static boolean removeServer(ServerInfo si) {
        return metaMessage(MetaMessageType.REMOVE, si, null);
    }

    /**
     * Update a public server.
     *
     * @return True if the server was updated.
     */
    public static boolean updateServer(ServerInfo si) {
        return metaMessage(MetaMessageType.UPDATE, si, null);
    }
}