package net.atomarea.flowx.xmpp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import net.atomarea.flowx.ServerConfig;
import net.atomarea.flowx.data.Account;
import net.atomarea.flowx.data.ChatHistory;
import net.atomarea.flowx.data.ChatMessage;
import net.atomarea.flowx.data.Data;
import net.atomarea.flowx.ui.activities.ChatHistoryActivity;
import net.atomarea.flowx.ui.activities.ChatListActivity;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.roster.packet.RosterPacket;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;

import java.io.IOException;
import java.io.Serializable;

/**
 * Created by Tom on 06.08.2016.
 */
public class ServerConnection implements Serializable, StanzaListener {

    private static final String TAG = "FX XMPP";

    private XMPPTCPConnection xmppConnection;
    private String LocalUser;

    // TODO <message id='185339fa-962f-418f-aa3b-6a763eea1f37' type='chat' to='tom@flowx.im' from='dom1nic@flowx.im/mobile'><body>https://flowx.im:5281/upload/35d21ccf-f9c0-4e79-966c-742279821c45/20160807_132659588_1853.jpg</body><markable xmlns='urn:xmpp:chat-markers:0'/><request xmlns='urn:xmpp:receipts'/><x xmlns='jabber:x:oob'><url>https://flowx.im:5281/upload/35d21ccf-f9c0-4e79-966c-742279821c45/20160807_132659588_1853.jpg</url></x></message>

    private Handler postHandler;

    public void login(String username, String password) throws SmackException, IOException, XMPPException {
        XMPPTCPConnectionConfiguration.Builder config = XMPPTCPConnectionConfiguration.builder();
        config.setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible);
        config.setServiceName(ServerConfig.ServerIP);
        config.setHost(ServerConfig.ServerIP);
        config.setPort(ServerConfig.ServerPort);
        config.setDebuggerEnabled(true);
        config.setResource("FlowX-App");

        LocalUser = username + "@flowx.im/FlowX-App";

        ProviderManager.addExtensionProvider(ReceivedReceipt.ELEMENT, ReceivedReceipt.NAMESPACE, new ReceivedReceipt.Provider());
        ProviderManager.addExtensionProvider(ReadReceipt.ELEMENT, ReadReceipt.NAMESPACE, new ReadReceipt.Provider());

        xmppConnection = new XMPPTCPConnection(config.build());
        xmppConnection.addAsyncStanzaListener(this, null);
        xmppConnection.connect();
        xmppConnection.login(username, password);

        Data.setConnection(this);

        new Thread() { // TODO: CHANGE TO ANDROID SERVICE :)
            public void run() {
                Looper.prepare();
                postHandler = new Handler();

                while (true) {
                    Looper.loop();
                }
            }
        }.start();
    }

    @Override
    public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
        String from = null;
        if (packet.getFrom() != null)
            from = packet.getFrom().split("/")[0];
        if (packet instanceof RosterPacket) {
            RosterPacket rosterPacket = (RosterPacket) packet;
            for (RosterPacket.Item i : rosterPacket.getRosterItems()) {
                Log.i(TAG, i.getName() + " " + i.getUser());
                Data.getContacts().add(new Account(i.getName(), i.getUser(), null));
            }
        } else if (packet instanceof Message) {
            Message message = (Message) packet;
            Log.i(TAG, "Message: " + message.getFrom() + " : " + message.getBody());
            if (message.getType().equals(Message.Type.chat) && message.getBody() != null) {
                Account contact = Data.getAccountByXmpp(from);
                if (contact != null) {
                    ChatHistory chatHistory = Data.getChatHistory(contact);
                    if (chatHistory != null) {
                        ChatMessage chatMessage = new ChatMessage(message.getStanzaId(), message.getBody(), ChatMessage.Type.Text, false, System.currentTimeMillis());
                        chatHistory.getChatMessages().add(chatMessage);
                        ChatListActivity.doRefresh();
                        ChatHistoryActivity.doRefresh();
                        sendReceivedMarker(contact, chatMessage);
                    }
                }
            } else if (message.getBody() != null) {
                Log.i(TAG, "RECV " + message.getBody());
            } else {
                for (ExtensionElement ee : message.getExtensions()) {
                    if (ee.getNamespace().equals(ReceivedReceipt.NAMESPACE)) {
                        ReceivedReceipt rr = (ReceivedReceipt) ee;
                        ChatMessage chatMessage = Data.getChatMessage(rr.getID());
                        if (chatMessage != null) {
                            chatMessage.setState(ChatMessage.State.DeliveredToContact);
                            ChatHistoryActivity.doRefresh();
                        }
                    }
                    if (ee.getNamespace().equals(ReadReceipt.NAMESPACE)) {
                        ReadReceipt rr = (ReadReceipt) ee;
                        ChatMessage chatMessage = Data.getChatMessage(rr.getID());
                        if (chatMessage != null) {
                            chatMessage.setState(ChatMessage.State.ReadByContact);
                            ChatHistoryActivity.doRefresh();
                        }
                    }
                }
                //ChatListActivity.doRefresh();
                ChatHistoryActivity.doRefresh();
            }
        } else if (packet instanceof Presence) {
            Presence presence = (Presence) packet;
            Log.d(TAG, "Presence: " + presence.getFrom() + " " + presence.getStatus());
            Account contact = Data.getAccountByXmpp(from);
            if (contact != null && presence.getStatus() != null)
                contact.setStatus(presence.getStatus());
        } else Log.d(TAG, "Packet: " + packet.getClass().getSimpleName());
    }

    public void sendMessage(final Account contact, final ChatMessage chatMessage) {
        postHandler.post(new Runnable() {
            @Override
            public void run() {
                Message message = new Message();
                message.setFrom(LocalUser);
                message.setTo(contact.getXmppAddress());
                message.setBody(chatMessage.getData());
                message.setType(Message.Type.chat);
                message.setSubject(chatMessage.getType().name());
                message.setStanzaId(chatMessage.getID());
                send(message);
            }
        });
    }

    public void sendReceivedMarker(final Account contact, final ChatMessage chatMessage) {
        postHandler.post(new Runnable() {
            @Override
            public void run() {
                Message message = new Message();
                message.setFrom(LocalUser);
                message.setTo(contact.getXmppAddress());
                message.setBody(null);
                message.setSubject(ReceivedReceipt.NAMESPACE);
                message.setType(Message.Type.chat);
                message.setStanzaId(chatMessage.getID());
                message.addExtension(new ReceivedReceipt(chatMessage.getID()));
                chatMessage.setState(ChatMessage.State.DeliveredToContact);
                send(message);
            }
        });
    }

    public void sendReadMarker(final ChatHistory chatHistory) {
        postHandler.post(new Runnable() {
            @Override
            public void run() {
                for (ChatMessage chatMessage : chatHistory.getChatMessages()) {
                    if (!chatMessage.isSent()) {
                        if (!chatMessage.getState().equals(ChatMessage.State.ReadByContact)) {
                            Message message = new Message();
                            message.setFrom(LocalUser);
                            message.setTo(chatHistory.getRemoteContact().getXmppAddress());
                            message.setBody(null);
                            message.setSubject(ReadReceipt.NAMESPACE);
                            message.setType(Message.Type.chat);
                            message.setStanzaId(chatMessage.getID());
                            message.addExtension(new ReadReceipt(chatMessage.getID()));
                            chatMessage.setState(ChatMessage.State.ReadByContact);
                            send(message);
                        }
                    }
                }
            }
        });
    }

    public void sendContactsUpdate() {
        postHandler.post(new Runnable() {
            @Override
            public void run() {
                RosterPacket rp = new RosterPacket();
                rp.setFrom(LocalUser);
                for (Account contact : Data.getContacts()) {
                    rp.getRosterItems().add(new RosterPacket.Item(contact.getXmppAddress(), contact.getName()));
                }
                try {
                    xmppConnection.sendStanza(rp);
                } catch (SmackException.NotConnectedException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void send(Message message) {
        try {
            xmppConnection.sendStanza(message);
        } catch (SmackException.NotConnectedException e) {
            e.printStackTrace();
        }
    }

    public void disconnect() {
        postHandler.post(new Runnable() {
            @Override
            public void run() {
                xmppConnection.disconnect();
            }
        });
    }

    public String getLocalUser() {
        return LocalUser;
    }
}
