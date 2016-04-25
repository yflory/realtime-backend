package org.xwiki.contrib.realtime.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.websocket.WebSocket;
import org.xwiki.contrib.websocket.WebSocketHandler;
import org.xwiki.model.reference.DocumentReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.inject.Named;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

//import java.util.

@Component
@Named("realtimeNetflux")
public class NetfluxBackend implements WebSocketHandler
{
    private static final long TIMEOUT_MILLISECONDS = 30000;
    private static final boolean USE_HISTORY_KEEPER = true;

    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Channel> channelByName = new HashMap<String, Channel>();

    private final String historyKeeper = getRandomHexString((16));
    private final Object bigLock = new Object();

    private final UserBox users = new UserBox();

    /**
     * Store/remove/get users in memory
     */
    private static class UserBox
    {
        private Map<WebSocket, User> userBySocket = new HashMap<WebSocket, User>();
        private Map<String, User> userByName = new HashMap<>();

        /**
         * Get a User by his name
         * @param name the user name
         * @return
         */
        User byName(String name) {
            return userByName.get(name);
        }

        /**
         * Get a user from a socket
         * @param sock the WebSocket
         * @return
         */
        User bySocket(WebSocket sock) {
            return userBySocket.get(sock);
        }

        /**
         * Remove a user from memory
         * @param u the User
         * @return
         */
        boolean removeUser(User u) {
            if(userBySocket.get(u.sock) == null && userByName.get(u.name) == null) {
                return false;
            }
            if(userBySocket.remove(u.sock) == null) { throw new RuntimeException("userBySocket does not contain user"); }
            if(userByName.remove(u.name) == null) { throw new RuntimeException("userByName does not contain user"); }
            return true;
        }

        /**
         * Add a user in memory
         * @param u the User
         */
        void addUser(User u) {
            userBySocket.put(u.sock, u);
            userByName.put(u.name, u);
        }
    }

    private static class Channel
    {
        final Map<String, User> users = new HashMap<String, User>();
        final List<String> messages = new LinkedList<String>();
        final String name;
        Channel(String name) {
            this.name = name;
        }
    }

    private static class User
    {
        final WebSocket sock;
        final String name;
        final Queue<String> toBeSent = new LinkedList<>();
        final Set<Channel> chans = new HashSet<Channel>();
        boolean connected;
        long timeOfLastMessage;

        User(WebSocket ws, String name)
        {
            this.sock = ws;
            this.name = name;
            this.connected = true;
        }
    }

    /**
     * Handler called when a socket is closed/disconnected
     * @param ws the WebSocket
     */
    private void wsDisconnect(WebSocket ws)
    {
        synchronized (bigLock) {

            User user = users.bySocket(ws);

            if (user == null) { return; }

            // System.out.println("Disconnect "+user.name);
            users.removeUser(user);
            user.connected = false;

            for (Channel chan : user.chans) {
                chan.users.remove(user.name);
                List<Object> leaveMsg = buildDefault(user.name, "LEAVE", chan.name, "Quit: [ wsDisconnect() ]");
                String msgStr = display(leaveMsg);
                sendChannelMessage("LEAVE", user, chan, msgStr);
                chan.messages.add(msgStr);
                // Remove the channel when there is no user anymore (the history keeper doesn't count)
                Integer minSize = (USE_HISTORY_KEEPER) ? 1 : 0;
                if (chan.users.keySet().size() == minSize) {
                    channelByName.remove(chan.name);
                }
            }
        }
    }

    private String getRandomHexString(int numchars){
        Random r = new Random();
        StringBuffer sb = new StringBuffer();
        while(sb.length() < numchars){
            sb.append(Integer.toHexString(r.nextInt()));
        }

        return sb.toString().substring(0, numchars);
    }

    private String display(List<Object> list) {
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize message", e);
        }
    }

    /**
     * Add a message to the sending queue of a User
     * @param toUser the User
     * @param msgStr the string message
     */
    private void sendMessage(User toUser, String msgStr) {
        toUser.toBeSent.add(msgStr);
    }

    /**
     * Broadcast a message to a channel
     * @param cmd the message type/command
     * @param me the sender
     * @param chan the channel where the message is sent
     * @param msgStr the message
     */
    private void sendChannelMessage(String cmd, User me, Channel chan, String msgStr) {
        for (User u : chan.users.values()) {
            //System.out.println("Sending to " + clientName + "  " + msgStr);
            if (u != null && (cmd != "MSG" || !u.equals(me))) {
                sendMessage(u, msgStr);
            }
        }
        if(USE_HISTORY_KEEPER && cmd == "MSG") {
            //System.out.println("Added in history : "+msgStr);
            chan.messages.add(msgStr);
        }
    }

    /*
     * The following function are used to build the different types of messages sent by the server :
     * ACK, JACK (Join-ACK), JOIN, LEAVE, MSG, ERROR
     */
    private ArrayList<Object> buildAck (Integer seq) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(seq);
        msg.add("ACK");
        return msg;
    }
    private ArrayList<Object> buildJack (Integer seq, String obj) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(seq);
        msg.add("JACK");
        msg.add(obj);
        return msg;
    }
    private ArrayList<Object> buildDefault (String userId, String cmd, String chanName, String reason) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(0);
        msg.add(userId);
        msg.add(cmd);
        msg.add(chanName);
        if(reason != null) {
            msg.add(reason);
        }
        return msg;
    }
    private ArrayList<Object> buildMessage(Integer seq, String userId, String obj, Object msgStr) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(0);
        msg.add(userId);
        msg.add("MSG");
        msg.add(obj);
        msg.add(msgStr);
        return msg;
    }
    private ArrayList<Object> buildError(Integer seq, String errorType, String errorMessage) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(seq);
        msg.add("ERROR");
        msg.add(errorType);
        msg.add(errorMessage);
        return msg;
    }

    /**
     * Handler called when a message is received by the server from a socket
     * @param ws the socket from which the message is received
     */
    private void onMessage(WebSocket ws) {
        ArrayList<Object> msg;
        try {
            msg = mapper.readValue(ws.recv(), ArrayList.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse message", e);
        }
        if (msg == null) { return; }

        User user = users.bySocket(ws);

        if (user == null) { wsDisconnect(ws); return; }

        long now = System.currentTimeMillis();
        if (user != null) {
            user.timeOfLastMessage = now;
        }

        // It's way too much of a pain to hunt down the setTimeout() equiv
        // in netty so I'm just going to run the check every time something comes in
        // on the websocket to disconnect anyone who hasn't written to the WS in
        // more than 30 seconds.
        List<WebSocket> socks = new LinkedList<WebSocket>(users.userBySocket.keySet());
        for (WebSocket sock : socks) {
            if (now - users.bySocket(sock).timeOfLastMessage > TIMEOUT_MILLISECONDS) {
                wsDisconnect(sock);
            }
        }

        Integer seq = (Integer) msg.get(0);
        String cmd = msg.get(1).toString();
        String obj = (msg.get(2) != null) ? msg.get(2).toString() : null;

        /*
         * JOIN request:
         * - Send a JACK
         * - Join or create the channel
         * - Send a JOIN message to the selected channel
         */
        if(cmd.equals("JOIN")) {
            if (obj != null && obj.length() == 0) {
                ArrayList<Object> errorMsg = buildError(seq, "ENOENT", "");
                sendMessage(user, display(errorMsg));
                return;
            }
            else if (obj == null) {
                obj = getRandomHexString(32);
            }
            Channel chan = channelByName.get(obj);
            if (chan == null) {
                if(USE_HISTORY_KEEPER) {
                    chan = new Channel(obj);
                    chan.users.put(historyKeeper, null);
                }
                channelByName.put(obj, chan);
            }
            ArrayList<Object> jackMsg = buildJack(seq, chan.name);
            sendMessage(user, display(jackMsg));
            user.chans.add(chan);
            for(String userId : chan.users.keySet()) {
                ArrayList<Object> inChannelMsg = buildDefault(userId, "JOIN", obj, null);
                sendMessage(user, display(inChannelMsg));
            }
            chan.users.put(user.name, user);
            ArrayList<Object> joinMsg = buildDefault(user.name, "JOIN", obj, null);
            sendChannelMessage("JOIN", user, chan, display(joinMsg));
            return;
        }
        /*
         * LEAVE request:
         * - Check if the request is correct
         * - Send an ACK
         * - Leave the channel
         * - Send a LEAVE message to the selected channel
         */
        if(cmd.equals("LEAVE")) {
            ArrayList<Object> errorMsg = null;
            if (obj == null || obj.length() == 0)
                errorMsg = buildError(seq, "EINVAL", "undefined");
            if (errorMsg != null && channelByName.get(obj) == null)
                errorMsg = buildError(seq, "ENOENT", obj);
            if (errorMsg != null && !channelByName.get(obj).users.containsKey(user.name))
                errorMsg = buildError(seq, "NOT_IN_CHAN", obj);
            if (errorMsg != null) {
                sendMessage(user, display(errorMsg));
                return;
            }
            ArrayList<Object> ackMsg = buildAck(seq);
            sendMessage(user, display(ackMsg));
            Channel chan = channelByName.get(obj);
            ArrayList<Object> leaveMsg = buildDefault(user.name, "LEAVE", obj, "");
            sendChannelMessage("LEAVE", user, chan, display(leaveMsg));
            chan.users.remove(user.name);
            user.chans.remove(chan);
        }
        /*
         * PING:
         * - Send an ACK
         */
        if(cmd.equals("PING")) {
            ArrayList<Object> ackMsg = buildAck(seq);
            sendMessage(user, display(ackMsg));
        }
        /*
         * MSG (patch):
         * - Send an ACK
         * - Check if the history of the channel is requested
         *    - Yes : send the history
         *    - No : transfer the message to the recipient
         */
        if(cmd.equals("MSG")) {
            ArrayList<Object> ackMsg = buildAck(seq);
            sendMessage(user, display(ackMsg));
            if(USE_HISTORY_KEEPER && obj.equals(historyKeeper)) {
                ArrayList<String> msgHistory;
                try {
                    msgHistory = new ObjectMapper().readValue(msg.get(3).toString(), ArrayList.class);
                } catch (IOException e) {
                    msgHistory = null;
                    e.printStackTrace();
                }
                String text = (msgHistory == null) ? "" : msgHistory.get(0);
                if(text.equals("GET_HISTORY")) {
                    String chanName = msgHistory.get(1);
                    Channel chan = channelByName.get(chanName);
                    if(chan != null && chan.messages != null && chan.messages.size() > 0) {
                        Integer i = 0;
                        for (String msgStr : chan.messages) {
                            sendMessage(user, msgStr);
                            i++;
                        }
                    }
                    ArrayList<Object> msgEndHistory = buildMessage(0, historyKeeper, user.name, 0);
                    sendMessage(user, display(msgEndHistory));
                }
                return;
            }
            if (obj.length() != 0 && channelByName.get(obj) == null && users.byName(obj) == null) {
                ArrayList<Object> errorMsg = buildError(seq, "ENOENT", obj);
                sendMessage(user, display(errorMsg));
                return;
            }
            if (channelByName.get(obj) != null) {
                ArrayList<Object> msgMsg = buildMessage(0, user.name, obj, msg.get(3));
                Channel chan = channelByName.get(obj);
                sendChannelMessage("MSG", user, chan, display(msgMsg));
                return;
            }
            if (users.byName(obj) != null) {
                ArrayList<Object> msgMsg = buildMessage(0, user.name, obj, msg.get(3));
                sendMessage(users.byName(obj), display(msgMsg));
                return;
            }
        }
    }
    
    private static class SendJob {
        User user;
        List<String> messages;
    }

    private SendJob getSendJob()
    {
        synchronized (bigLock) {
            for (User u : users.userByName.values()) {
                if (u.connected && !u.toBeSent.isEmpty()) {
                    SendJob out = new SendJob();
                    out.messages = new ArrayList<String>(u.toBeSent);
                    out.user = u;
                    u.toBeSent.clear();
                    return out;
                }
            }
            return null;
        }
    }

    public void onWebSocketConnect(WebSocket sock)
    {
        synchronized (bigLock) {
            User user = users.bySocket(sock);

            // Send the IDENT message
            if (user == null) { // Register the user
                String userName = getRandomHexString(32);
                user = new User(sock, userName);
                users.addUser(user);
                user.timeOfLastMessage = System.currentTimeMillis();
                //System.out.println("Registered " + userName);
                sock.onDisconnect(new WebSocket.Callback() {
                    public void call(WebSocket ws) {
                    synchronized(bigLock) {
                        wsDisconnect(ws);
                    }
                    }
                });
            }

            ArrayList<Object> identMsg = buildDefault("", "IDENT", user.name, null);
            String identMsgStr = display(identMsg);
            try {
                //System.out.println("Sending to " + user.name + " : " + identMsgStr);
                user.sock.send(identMsgStr);
            } catch (Exception e) {
                //System.out.println("Sending failed");
                wsDisconnect(user.sock);
                return;
            }

            sock.onMessage(new WebSocket.Callback() {
                public void call(WebSocket ws) {
                    SendJob sj;
                    synchronized (bigLock) {
                        onMessage(ws);
                        sj = getSendJob();
                    }
                    while (sj != null) {
                        for (String msg : sj.messages) {
                            if(!sj.user.connected) { break; }
                            try {
                                //System.out.println("Sending to " + sj.user.name + " : " + msg);
                                sj.user.sock.send(msg);
                            } catch (Exception e) {
                                //System.out.println("Sending failed " + msg);
                                wsDisconnect(sj.user.sock);
                                return;
                            }
                        }
                        sj = getSendJob();
                    }
                }
            });
        }
    }
}
