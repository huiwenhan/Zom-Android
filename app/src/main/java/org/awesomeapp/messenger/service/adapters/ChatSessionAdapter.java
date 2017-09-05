/*
 * Copyright (C) 2007-2008 Esmertec AG. Copyright (C) 2007-2008 The Android Open
 * Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.awesomeapp.messenger.service.adapters;

import  org.awesomeapp.messenger.crypto.IOtrChatSession;
import org.awesomeapp.messenger.crypto.otr.OtrChatListener;
import org.awesomeapp.messenger.crypto.otr.OtrChatManager;
import org.awesomeapp.messenger.crypto.otr.OtrChatSessionAdapter;
import org.awesomeapp.messenger.crypto.otr.OtrDataHandler;
import org.awesomeapp.messenger.crypto.otr.OtrDataHandler.Transfer;
import org.awesomeapp.messenger.crypto.otr.OtrDebugLogger;
import org.awesomeapp.messenger.model.Address;
import org.awesomeapp.messenger.plugin.xmpp.XmppAddress;
import  org.awesomeapp.messenger.service.IChatListener;
import org.awesomeapp.messenger.service.IDataListener;

import eu.siacs.conversations.Downloader;
import im.zom.messenger.R;
import info.guardianproject.iocipher.File;

import org.awesomeapp.messenger.ImApp;
import org.awesomeapp.messenger.model.ChatGroup;
import org.awesomeapp.messenger.model.ChatGroupManager;
import org.awesomeapp.messenger.model.ChatSession;
import org.awesomeapp.messenger.model.Contact;
import org.awesomeapp.messenger.model.GroupListener;
import org.awesomeapp.messenger.model.GroupMemberListener;
import org.awesomeapp.messenger.model.ImConnection;
import org.awesomeapp.messenger.model.ImEntity;
import org.awesomeapp.messenger.model.ImErrorInfo;
import org.awesomeapp.messenger.model.MessageListener;
import org.awesomeapp.messenger.model.Presence;
import org.awesomeapp.messenger.provider.Imps;
import org.awesomeapp.messenger.util.SecureMediaStore;
import org.awesomeapp.messenger.util.SystemServices;

import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.java.otr4j.OtrEngineListener;
import net.java.otr4j.session.SessionID;
import net.java.otr4j.session.SessionStatus;

import org.awesomeapp.messenger.service.RemoteImService;
import org.awesomeapp.messenger.service.StatusBarNotifier;
import org.jivesoftware.smack.util.StringUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.stringprep.XmppStringprepException;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.util.Log;
import android.webkit.MimeTypeMap;

import static cz.msebera.android.httpclient.conn.ssl.SSLConnectionSocketFactory.TAG;

public class ChatSessionAdapter extends org.awesomeapp.messenger.service.IChatSession.Stub {

    private static final String NON_CHAT_MESSAGE_SELECTION = Imps.Messages.TYPE + "!="
                                                             + Imps.MessageType.INCOMING + " AND "
                                                             + Imps.Messages.TYPE + "!="
                                                             + Imps.MessageType.OUTGOING;

    /** The registered remote listeners. */
    final RemoteCallbackList<IChatListener> mRemoteListeners = new RemoteCallbackList<IChatListener>();

    ImConnectionAdapter mConnection;
    ChatSessionManagerAdapter mChatSessionManager;


    ChatSession mChatSession;
    ListenerAdapter mListenerAdapter;
    boolean mIsGroupChat;
    StatusBarNotifier mStatusBarNotifier;

    private ContentResolver mContentResolver;
    /*package*/Uri mChatURI;

    private Uri mMessageURI;

    private boolean mConvertingToGroupChat;

    private static final int MAX_HISTORY_COPY_COUNT = 10;

    private HashMap<String, Integer> mContactStatusMap = new HashMap<String, Integer>();

    private boolean mHasUnreadMessages;

    private RemoteImService service = null;

    private HashMap<String, OtrChatSessionAdapter> mOtrChatSessions;
    private SessionStatus mLastSessionStatus = null;
    private OtrDataHandler mDataHandler;

    private IDataListener mDataListener;
    private DataHandlerListenerImpl mDataHandlerListener;

    private boolean mAcceptTransfer = false;
    private boolean mWaitingForResponse = false;
    private boolean mAcceptAllTransfer = true;//TODO set this via preference, but default true
    private String mLastFileUrl = null;


    private long mContactId;

    public ChatSessionAdapter(ChatSession chatSession, ImConnectionAdapter connection, boolean isNewSession) {

        mChatSession = chatSession;
        mConnection = connection;

        service = connection.getContext();
        mContentResolver = service.getContentResolver();
        mStatusBarNotifier = service.getStatusBarNotifier();
        mChatSessionManager = (ChatSessionManagerAdapter) connection.getChatSessionManager();

        mListenerAdapter = new ListenerAdapter();

        mOtrChatSessions = new HashMap<String, OtrChatSessionAdapter>();

        ImEntity participant = mChatSession.getParticipant();

        if (participant instanceof ChatGroup) {
            init((ChatGroup) participant,isNewSession);
        } else {
            init((Contact) participant,isNewSession);
            initOtrChatSession(participant);
        }
        
    }

    private void initOtrChatSession (ImEntity participant)
    {
        try
        {
            if (mConnection != null)
            {
                mDataHandler = new OtrDataHandler(mChatSession);
                mDataHandlerListener = new DataHandlerListenerImpl();
                mDataHandler.setDataListener(mDataHandlerListener);

                OtrChatManager cm = service.getOtrChatManager();
                cm.addOtrEngineListener(mListenerAdapter);
                mChatSession.setMessageListener(new OtrChatListener(cm, mListenerAdapter));

                if (participant instanceof Contact) {
                    String key = participant.getAddress().getAddress();
                    if (!mOtrChatSessions.containsKey(key)) {
                        OtrChatSessionAdapter adapter = new OtrChatSessionAdapter(mConnection.getLoginUser().getAddress().getAddress(), participant, cm);
                        mOtrChatSessions.put(key, adapter);
                    }
                }
                else if (participant instanceof ChatGroup)
                {


                }

                mDataHandler.setChatId(getId());

            }
        }
        catch (NullPointerException npe)
        {
            Log.e(ImApp.LOG_TAG,"error init OTR session",npe);
        }
    }

    public synchronized IOtrChatSession getDefaultOtrChatSession () {

        if (mOtrChatSessions.size() > 0)
            return mOtrChatSessions.entrySet().iterator().next().getValue();
        else
            return null;
    }

    private int lastPresence = -1;

    public void presenceChanged (int newPresence)
    {

        if (mChatSession.getParticipant() instanceof Contact) {
            ((Contact) mChatSession.getParticipant()).getPresence().setStatus(newPresence);

            if (lastPresence != newPresence && newPresence == Presence.AVAILABLE)
                sendPostponedMessages();

            lastPresence = newPresence;
        }

    }

    private void init(ChatGroup group, boolean isNewSession) {
        
        mIsGroupChat = true;

        mContactId = insertOrUpdateGroupContactInDb(group);
        group.addMemberListener(mListenerAdapter);
        mChatSession.setMessageListener(mListenerAdapter);

        try {            
            mChatSessionManager.getChatGroupManager().joinChatGroupAsync(group.getAddress(),group.getName());
        
            mMessageURI = Imps.Messages.getContentUriByThreadId(mContactId);
    
            mChatURI = ContentUris.withAppendedId(Imps.Chats.CONTENT_URI, mContactId);
    
            if (isNewSession)
                insertOrUpdateChat("");
    
            for (Contact c : group.getMembers()) {
                mContactStatusMap.put(c.getName(), c.getPresence().getStatus());
            }
            
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
        
    }

    private void init(Contact contact, boolean isNewSession) {
        mIsGroupChat = false;
        ContactListManagerAdapter listManager = (ContactListManagerAdapter) mConnection.getContactListManager();
        
        mContactId = listManager.queryOrInsertContact(contact);

        mChatURI = ContentUris.withAppendedId(Imps.Chats.CONTENT_URI, mContactId);

        if (isNewSession)
            insertOrUpdateChat(null);

        mMessageURI = Imps.Messages.getContentUriByThreadId(mContactId);

        mContactStatusMap.put(contact.getName(), contact.getPresence().getStatus());

        sendPostponedMessages();

    }

    public void reInit ()
    {
       // insertOrUpdateChat(null);

    }

    private ChatGroupManager getGroupManager() {
        return mConnection.getAdaptee().getChatGroupManager();
    }

    public ChatSession getAdaptee() {
        return mChatSession;
    }

    public Uri getChatUri() {
        return mChatURI;
    }

    public String[] getParticipants() {
        if (mIsGroupChat) {
            Contact self = mConnection.getLoginUser();
            ChatGroup group = (ChatGroup) mChatSession.getParticipant();
            List<Contact> members = group.getMembers();
            String[] result = new String[members.size() - 1];
            int index = 0;
            for (Contact c : members) {
                if (!c.equals(self)) {
                    result[index++] = c.getAddress().getAddress();
                }
            }
            return result;
        } else {

            return new String[] { mChatSession.getParticipant().getAddress().getAddress() };
        }
    }

    /**
     * Convert this chat session to a group chat. If it's already a group chat,
     * nothing will happen. The method works in async mode and the registered
     * listener will be notified when it's converted to group chat successfully.
     *
     * Note that the method is not thread-safe since it's always called from the
     * UI and Android uses single thread mode for UI.
     */
    public void convertToGroupChat(String nickname) {
        if (mIsGroupChat || mConvertingToGroupChat) {
            return;
        }

        mConvertingToGroupChat = true;
        new ChatConvertor().convertToGroupChat(nickname);
    }

    public boolean isGroupChatSession() {
        return mIsGroupChat;
    }

    public String getName() {

        if (isGroupChatSession())
            return ((ChatGroup)mChatSession.getParticipant()).getName();
        else
            return ((Contact)mChatSession.getParticipant()).getName();

    }

    public String getAddress() {
        return mChatSession.getParticipant().getAddress().getAddress();
    }

    public long getId() {
        return ContentUris.parseId(mChatURI);
    }

    public void inviteContact(String contact) {
        if (!mIsGroupChat) {
            return;
        }
        ContactListManagerAdapter listManager = (ContactListManagerAdapter) mConnection
                .getContactListManager();
        Contact invitee = new Contact(new XmppAddress(contact),contact,Imps.Contacts.TYPE_NORMAL);
        getGroupManager().inviteUserAsync((ChatGroup) mChatSession.getParticipant(), invitee);

    }

    public void leave() {
        if (mIsGroupChat) {
            getGroupManager().leaveChatGroupAsync((ChatGroup) mChatSession.getParticipant());
        }

        mContentResolver.delete(mMessageURI, null, null);
        mContentResolver.delete(mChatURI, null, null);
        mStatusBarNotifier.dismissChatNotification(mConnection.getProviderId(), getAddress());
        mChatSessionManager.closeChatSession(this);


    }

    public void leaveIfInactive() {
    //    if (mChatSession.getHistoryMessages().isEmpty()) {
            leave();
      //  }
    }

    public boolean sendKnock () {

        return mChatSession.sendKnock(mConnection.getLoginUser().getAddress().getAddress());
    }

    public void sendMessage(String text, boolean isResend) {

        if (mConnection.getState() != ImConnection.LOGGED_IN) {
            // connection has been suspended, save the message without send it
            long now = System.currentTimeMillis();
            insertMessageInDb(null, text, now, Imps.MessageType.QUEUED, null);
            return;
        }

        org.awesomeapp.messenger.model.Message msg = new org.awesomeapp.messenger.model.Message(text);
        msg.setID(nextID());

        msg.setFrom(mConnection.getLoginUser().getAddress());
        msg.setType(Imps.MessageType.QUEUED);

        long sendTime = System.currentTimeMillis();

        if (!isResend) {
            insertMessageInDb(null, text, sendTime, msg.getType(), 0, msg.getID(), null);
            insertOrUpdateChat(text);
        }

        int newType = mChatSession.sendMessageAsync(msg);

        if (msg.getDateTime() != null)
            sendTime = msg.getDateTime().getTime();

        updateMessageInDb(msg.getID(),newType,sendTime);


    }

    private void sendMediaMessage(String publishUrl, String localUrl, String mimeType) {

        String mediaPath = localUrl + ' ' + publishUrl;

        org.awesomeapp.messenger.model.Message msg = new org.awesomeapp.messenger.model.Message(publishUrl);
        msg.setID(nextID());

        msg.setFrom(mConnection.getLoginUser().getAddress());
        msg.setType(Imps.MessageType.QUEUED);

        long sendTime = System.currentTimeMillis();

        insertMessageInDb(null, mediaPath, sendTime, msg.getType(), 0, msg.getID(), mimeType);
        insertOrUpdateChat(mediaPath);

        int newType = mChatSession.sendMessageAsync(msg);

        if (msg.getDateTime() != null)
            sendTime = msg.getDateTime().getTime();

        updateMessageInDb(msg.getID(),newType,sendTime);


    }

    private void sendUnstoredMessage(String text) {

        if (mConnection.getState() != ImConnection.LOGGED_IN) {
            return;
        }

        org.awesomeapp.messenger.model.Message msg = new org.awesomeapp.messenger.model.Message(text);
        msg.setID(nextID());

        msg.setFrom(mConnection.getLoginUser().getAddress());
        msg.setType(Imps.MessageType.QUEUED);

        long sendTime = System.currentTimeMillis();

        int newType = mChatSession.sendMessageAsync(msg);


    }

    @Override
    public boolean offerData(String offerId, final String mediaUri, final String mimeType) {
        if (mConnection.getState() == ImConnection.SUSPENDED) {
            // TODO send later
            return false;
        }

        if (mChatSession.canOmemo() || mChatSession.getParticipant() instanceof ChatGroup)
        {
            //TODO do HTTP Upload XEP 363
            //this is ugly... we need a nice async task!
            new Thread ()
            {

                public void run ()
                {

                    File fileLocal = new File(Uri.parse(mediaUri).getPath());
                    String fileName = fileLocal.getName();
                    if (!fileName.contains("."))
                    {
                        fileName += "." + MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                    }

                    try
                    {
                        String resultUrl = mConnection.publishFile(fileName, mimeType, fileLocal.length(), new info.guardianproject.iocipher.FileInputStream(fileLocal));

                        if (resultUrl != null)
                            sendMediaMessage(resultUrl, mediaUri, mimeType);
                    }
                    catch (FileNotFoundException fe)
                    {
                        Log.w(TAG,"couldn't find file to share",fe);
                    }
                }
            }.start();

            return true;

        }
        else {
            HashMap<String, String> headers = null;
            if (mimeType != null) {
                headers = new HashMap<>();
                headers.put("Mime-Type", mimeType);
            }

            try {
                Address localUser = mConnection.getLoginUser().getAddress();

                if (mChatSession.getParticipant() instanceof Contact) {

                    Address remoteUser = new XmppAddress(getDefaultOtrChatSession().getRemoteUserId());
                    mDataHandler.offerData(offerId, localUser, remoteUser, Uri.parse(mediaUri).getPath(), headers);

                    insertMessageInDb(null, mediaUri, System.currentTimeMillis(), Imps.MessageType.OUTGOING_ENCRYPTED_VERIFIED, 0, offerId, mimeType);

                    return true;
                }
                else
                {
                    Log.w(ImApp.LOG_TAG, "can't send OTRDATA shares to groups");

                    return false;
                }
            } catch (Exception ioe) {
                Log.w(ImApp.LOG_TAG, "unable to offer data", ioe);
                return false;
            }
        }

    }

    /**
     * Sends a message to other participant(s) in this session without adding it
     * to the history.
     *
     *
     */
    /*
    public void sendMessageWithoutHistory(String text) {

     Message msg = new Message(text);
     // TODO OTRCHAT use a lower level method
     mChatSession.sendMessageAsync(msg);
    }*/

    /**
    boolean hasPostponedMessages() {
        String[] projection = new String[] { BaseColumns._ID, Imps.Messages.BODY,
                Imps.Messages.PACKET_ID,
                Imps.Messages.DATE, Imps.Messages.TYPE, Imps.Messages.IS_DELIVERED };
        String selection = Imps.Messages.TYPE + "=?";

        boolean result = false;

        Cursor c = mContentResolver.query(mMessageURI, projection, selection,
                new String[] { Integer.toString(Imps.MessageType.QUEUED) }, null);
        if (c == null) {
            RemoteImService.debug("Query error while querying postponed messages");
            return false;
        }
        else if (c.getCount() > 0)
        {
            result = true;
        }

        c.close();
        return true;

    }**/

    boolean sendingPostponed = false;

    void sendPostponedMessages() {

        if (!sendingPostponed) {
            sendingPostponed = true;

            String[] projection = new String[]{BaseColumns._ID, Imps.Messages.BODY,
                    Imps.Messages.PACKET_ID,
                    Imps.Messages.DATE, Imps.Messages.TYPE, Imps.Messages.IS_DELIVERED};
            String selection = Imps.Messages.TYPE + "=?";

            Cursor c = mContentResolver.query(mMessageURI, projection, selection,
                    new String[]{Integer.toString(Imps.MessageType.QUEUED)}, null);
            if (c == null) {
                RemoteImService.debug("Query error while querying postponed messages");
                return;
            }

            if (c.getCount() > 0) {
                ArrayList<String> messages = new ArrayList<String>();

                while (c.moveToNext())
                    messages.add(c.getString(1));

                removeMessageInDb(Imps.MessageType.QUEUED);

                for (String body : messages)
                    sendMessage(body, false);
            }

            c.close();

            sendingPostponed = false;
        }
    }

    public void registerChatListener(IChatListener listener) {
        if (listener != null) {
            mRemoteListeners.register(listener);

            if (mDataHandlerListener != null)
                mDataHandlerListener.checkLastTransferRequest ();
        }
    }

    public void unregisterChatListener(IChatListener listener) {
        if (listener != null) {
            mRemoteListeners.unregister(listener);
        }
    }

    public void markAsRead() {
        if (mHasUnreadMessages) {

            /**
             * we want to keep the last message now
            ContentValues values = new ContentValues(1);
            values.put(Imps.Chats.LAST_UNREAD_MESSAGE, (String) null);
            mConnection.getContext().getContentResolver().update(mChatURI, values, null, null);
*/
            String baseUsername = mChatSession.getParticipant().getAddress().getBareAddress();
            mStatusBarNotifier.dismissChatNotification(mConnection.getProviderId(), baseUsername);

            mHasUnreadMessages = false;
        }
    }

    String getNickName(String username) {
        ImEntity participant = mChatSession.getParticipant();
        if (mIsGroupChat) {
            
            ChatGroup group = (ChatGroup) participant;
            /**
            List<Contact> members = group.getMembers();
            for (Contact c : members) {
                if (username.equals(c.getAddress().getAddress())) {
                    
                    return c.getAddress().getResource();
                        
                }
            }**/
            Contact groupMember = group.getMember(username);
            if (groupMember != null)
            {
                return groupMember.getName();
            }
            else {
                // not found, impossible
                String[] parts = username.split("/");
                return parts[parts.length - 1];
            }
        } else {
            return ((Contact) participant).getName();
        }
    }

    void onConvertToGroupChatSuccess(ChatGroup group) {
        Contact oldParticipant = (Contact) mChatSession.getParticipant();
        String oldAddress = getAddress();
    //    mChatSession.setParticipant(group);
        mChatSessionManager.updateChatSession(oldAddress, this);

        Uri oldChatUri = mChatURI;
        Uri oldMessageUri = mMessageURI;
        init(group,false);
        //copyHistoryMessages(oldParticipant);

        mContentResolver.delete(oldMessageUri, NON_CHAT_MESSAGE_SELECTION, null);
        mContentResolver.delete(oldChatUri, null, null);

        mListenerAdapter.notifyChatSessionConverted();
        mConvertingToGroupChat = false;
    }

    /**
    private void copyHistoryMessages(Contact oldParticipant) {
        List<org.awesomeapp.messenger.model.Message> historyMessages = mChatSession.getHistoryMessages();
        int total = historyMessages.size();
        int start = total > MAX_HISTORY_COPY_COUNT ? total - MAX_HISTORY_COPY_COUNT : 0;
        for (int i = start; i < total; i++) {
            org.awesomeapp.messenger.model.Message msg = historyMessages.get(i);
            boolean incoming = msg.getFrom().equals(oldParticipant.getAddress());
            String contact = incoming ? oldParticipant.getName() : null;
            long time = msg.getDateTime().getTime();
            insertMessageInDb(contact, msg.getBody(), time, incoming ? Imps.MessageType.INCOMING
                                                                    : Imps.MessageType.OUTGOING);
        }
    }*/

    void insertOrUpdateChat(String message) {

        ContentValues values = new ContentValues(2);

        values.put(Imps.Chats.LAST_MESSAGE_DATE, System.currentTimeMillis());
        values.put(Imps.Chats.LAST_UNREAD_MESSAGE, message);

         values.put(Imps.Chats.GROUP_CHAT, mIsGroupChat);
         // ImProvider.insert() will replace the chat if it already exist.
         mContentResolver.insert(mChatURI, values);
         

    }

    // Pattern for recognizing a URL, based off RFC 3986
    private static final Pattern urlPattern = Pattern.compile(
            "(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)"
                    + "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
                    + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern aesGcmUrlPattern = Pattern.compile(
            "(?:^|[\\W])(aesgcm:\\/\\/|www\\.)"
                    + "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
                    + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);


    String checkForLinkedMedia (String jid, String message, boolean allowWebDownloads)
    {
        Matcher matcher = aesGcmUrlPattern.matcher(message);

        //if we match the aesgcm crypto pattern, then it is a match
        if (matcher.find())
        {
            int matchStart = matcher.start(1);
            int matchEnd = matcher.end();
            return message.substring(matchStart,matchEnd);
        }
        else if (allowWebDownloads)
        {
            //if someone sends us a random URL, only get it if it is from the same host as the jabberid
            matcher = urlPattern.matcher(message);
            if (matcher.find())
            {
                int matchStart = matcher.start(1);
                int matchEnd = matcher.end();
                String urlDownload = message.substring(matchStart,matchEnd);
                try {
                    String domain = JidCreate.bareFrom(jid).getDomain().toString();
                    if (urlDownload.contains(domain))
                        return urlDownload;
                }
                catch (XmppStringprepException se)
                {
                    //This shouldn't happeN!
                }
            }
        }

        return null;

    }

    private long insertOrUpdateGroupContactInDb(ChatGroup group) {
        // Insert a record in contacts table
        ContentValues values = new ContentValues(4);
        values.put(Imps.Contacts.USERNAME, group.getAddress().getAddress());
        values.put(Imps.Contacts.NICKNAME, group.getName());
        values.put(Imps.Contacts.CONTACTLIST, ContactListManagerAdapter.LOCAL_GROUP_LIST_ID);
        values.put(Imps.Contacts.TYPE, Imps.Contacts.TYPE_GROUP);

        Uri contactUri = ContentUris.withAppendedId(
                ContentUris.withAppendedId(Imps.Contacts.CONTENT_URI, mConnection.mProviderId),
                mConnection.mAccountId);
      
        ContactListManagerAdapter listManager = (ContactListManagerAdapter) mConnection
                .getContactListManager();
        
        long id = listManager.queryGroup(group);
        
        if (id == -1)
        {
            id = ContentUris.parseId(mContentResolver.insert(contactUri, values));
        
            ArrayList<ContentValues> memberValues = new ArrayList<ContentValues>();
            Contact self = mConnection.getLoginUser();
            for (Contact member : group.getMembers()) {
                if (!member.equals(self)) { // avoid to insert the user himself
                    ContentValues memberValue = new ContentValues(2);
                    memberValue.put(Imps.GroupMembers.USERNAME, member.getAddress().getAddress());
                    memberValue.put(Imps.GroupMembers.NICKNAME, member.getName());
                    memberValues.add(memberValue);
                }
            }
            if (!memberValues.isEmpty()) {
                ContentValues[] result = new ContentValues[memberValues.size()];
                memberValues.toArray(result);
                Uri memberUri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, id);
                mContentResolver.bulkInsert(memberUri, result);
            }
        }

        return id;
    }

    void insertGroupMemberInDb(Contact member) {

        if (mChatURI != null) {
            ContentValues values1 = new ContentValues(2);
            values1.put(Imps.GroupMembers.USERNAME, member.getAddress().getAddress());
            values1.put(Imps.GroupMembers.NICKNAME, member.getName());
            ContentValues values = values1;

            long groupId = ContentUris.parseId(mChatURI);
            Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);
            mContentResolver.insert(uri, values);

          //  insertMessageInDb(member.getName(), null, System.currentTimeMillis(),
              //      Imps.MessageType.PRESENCE_AVAILABLE);
        }
    }

    void deleteAllGroupMembers() {

        if (mChatURI != null) {
            long groupId = ContentUris.parseId(mChatURI);
            Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);
            mContentResolver.delete(uri, null, null);
        }
        //  insertMessageInDb(member.getName(), null, System.currentTimeMillis(),
        //    Imps.MessageType.PRESENCE_UNAVAILABLE);
    }

    void deleteGroupMemberInDb(Contact member) {
        String where = Imps.GroupMembers.USERNAME + "=?";
        String[] selectionArgs = { member.getAddress().getAddress() };

        if (mChatURI != null) {
            long groupId = ContentUris.parseId(mChatURI);
            Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);
            mContentResolver.delete(uri, where, selectionArgs);
        }
      //  insertMessageInDb(member.getName(), null, System.currentTimeMillis(),
            //    Imps.MessageType.PRESENCE_UNAVAILABLE);
    }

    void insertPresenceUpdatesMsg(String contact, Presence presence) {
        int status = presence.getStatus();

        Integer previousStatus = mContactStatusMap.get(contact);
        if (previousStatus != null && previousStatus == status) {
            // don't insert the presence message if it's the same status
            // with the previous presence update notification
            return;
        }

        mContactStatusMap.put(contact, status);
        int messageType;
        switch (status) {
        case Presence.AVAILABLE:
            messageType = Imps.MessageType.PRESENCE_AVAILABLE;
            break;

        case Presence.AWAY:
        case Presence.IDLE:
            messageType = Imps.MessageType.PRESENCE_AWAY;
            break;

        case Presence.DO_NOT_DISTURB:
            messageType = Imps.MessageType.PRESENCE_DND;
            break;

        default:
            messageType = Imps.MessageType.PRESENCE_UNAVAILABLE;
            break;
        }

        if (mIsGroupChat) {
            insertMessageInDb(contact, null, System.currentTimeMillis(), messageType, null);
        } else {
            insertMessageInDb(null, null, System.currentTimeMillis(), messageType, null);
        }
    }

    void removeMessageInDb(int type) {
        mContentResolver.delete(mMessageURI, Imps.Messages.TYPE + "=?",
                new String[] { Integer.toString(type) });
    }

    Uri insertMessageInDb(String contact, String body, long time, int type, String mimeType) {
        return insertMessageInDb(contact, body, time, type, 0/*No error*/, nextID(), mimeType);
    }

    /**
     * A prefix helps to make sure that ID's are unique across mutliple instances.
     */
    private static String prefix = StringUtils.randomString(5) + "-";

    /**
     * Keeps track of the current increment, which is appended to the prefix to
     * forum a unique ID.
     */
    private static long id = 0;

    static String nextID ()
    {
        return prefix + Long.toString(id++);
    }

    Uri insertMessageInDb(String contact, String body, long time, int type, int errCode, String id, String mimeType) {
        boolean isEncrypted = false;
        if (getDefaultOtrChatSession() != null) {
            try {
                isEncrypted = getDefaultOtrChatSession().isChatEncrypted();
            } catch (RemoteException e) {
                // Leave it as encrypted so it gets stored in memory
                // FIXME(miron)
            }
        }
        return Imps.insertMessageInDb(mContentResolver, mIsGroupChat, mContactId, isEncrypted, contact, body, time, type, errCode, id, mimeType);
    }

    int updateMessageInDb(String id, int type, long time) {

        return Imps.updateMessageInDb(mContentResolver, id, type, time, mContactId);
    }



    class ListenerAdapter implements MessageListener, GroupMemberListener, OtrEngineListener {

        public synchronized boolean onIncomingMessage(ChatSession ses, final org.awesomeapp.messenger.model.Message msg) {
            String body = msg.getBody();
            String username = msg.getFrom().getAddress();
            String bareUsername = msg.getFrom().getBareAddress();
            String nickname = getNickName(username);
            long time = msg.getDateTime().getTime();

            if (msg.getID() != null
                &&
                Imps.messageExists(mContentResolver, msg.getID()))
            {
                return false; //this message is a duplicate
            }

            boolean allowWebDownloads = true;
            String mediaLink = checkForLinkedMedia(username, body,allowWebDownloads);

            if (mediaLink == null) {
                insertOrUpdateChat(body);

                boolean wasMessageSeen = false;

                if (msg.getID() == null)
                    insertMessageInDb(nickname, body, time, msg.getType(), null);
                else
                    insertMessageInDb(nickname, body, time, msg.getType(), 0, msg.getID(), null);

                int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < N; i++) {
                    IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                    try {
                        boolean wasSeen = listener.onIncomingMessage(ChatSessionAdapter.this, msg);

                        if (wasSeen)
                            wasMessageSeen = wasSeen;

                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
                mRemoteListeners.finishBroadcast();

                // Due to the move to fragments, we could have listeners for ChatViews that are not visible on the screen.
                // This is for fragments adjacent to the current one.  Therefore we can't use the existence of listeners
                // as a filter on notifications.
                if (!wasMessageSeen) {
                    //reinstated body display here in the notification; perhaps add preferences to turn that off
                    mStatusBarNotifier.notifyChat(mConnection.getProviderId(), mConnection.getAccountId(),
                            getId(), bareUsername, nickname, body, false);
                }
            }
            else
            {
                try {
                    Downloader dl = new Downloader();
                    File fileDownload = dl.openSecureStorageFile(mContactId+"",mediaLink);

                    OutputStream storageStream = new info.guardianproject.iocipher.FileOutputStream(fileDownload);
                    boolean downloaded = dl.get(mediaLink, storageStream);

                    if (downloaded) {
                        String mimeType = dl.getMimeType();

                        try {
                            //boolean isVerified = getDefaultOtrChatSession().isKeyVerified(bareUsername);
                            //int type = isVerified ? Imps.MessageType.INCOMING_ENCRYPTED_VERIFIED : Imps.MessageType.INCOMING_ENCRYPTED;
                            int type = Imps.MessageType.INCOMING;
                            if (mediaLink.startsWith("aesgcm"))
                                type = Imps.MessageType.INCOMING_ENCRYPTED_VERIFIED;

                            Uri vfsUri = SecureMediaStore.vfsUri(fileDownload.getAbsolutePath());

                            insertOrUpdateChat(vfsUri.toString());

                            Uri messageUri = Imps.insertMessageInDb(service.getContentResolver(),
                                    mIsGroupChat, getId(),
                                    true, nickname,
                                    vfsUri.toString(), System.currentTimeMillis(), type,
                                    0, msg.getID(), mimeType);

                            int percent = (int) (100);

                            String[] path = mediaLink.split("/");
                            String sanitizedPath = SystemServices.sanitize(path[path.length - 1]);

                            final int N = mRemoteListeners.beginBroadcast();
                            for (int i = 0; i < N; i++) {
                                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                                try {
                                    listener.onIncomingFileTransferProgress(sanitizedPath, percent);
                                } catch (RemoteException e) {
                                    // The RemoteCallbackList will take care of removing the
                                    // dead listeners.
                                }
                            }
                            mRemoteListeners.finishBroadcast();

                            if (N == 0) {


                                mStatusBarNotifier.notifyChat(mConnection.getProviderId(), mConnection.getAccountId(),
                                        getId(), bareUsername, nickname, service.getString(R.string.file_notify_text, mimeType, nickname), false);
                            }
                        } catch (Exception e) {
                            Log.e(ImApp.LOG_TAG, "Error updating file transfer progress", e);
                        }
                    }
                    else
                    {
                        //file doesn't exist... ignore?
                    }

                }
                catch (Exception e)
                {
                    Log.e(ImApp.LOG_TAG,"error downloading incoming media",e);

                }
            }

            mHasUnreadMessages = true;
            return true;
        }

        public void onSendMessageError(ChatSession ses, final org.awesomeapp.messenger.model.Message msg, final ImErrorInfo error) {
            insertMessageInDb(null, null, System.currentTimeMillis(), Imps.MessageType.OUTGOING,
                    error.getCode(), null, null);

            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onSendMessageError(ChatSessionAdapter.this, msg, error);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        public void onSubjectChanged(ChatGroup group, String subject)
        {
            if (mChatURI != null) {
                ContentValues values1 = new ContentValues(1);
                values1.put(Imps.Contacts.NICKNAME,subject);
                ContentValues values = values1;

                Uri uriContact = ContentUris.withAppendedId(Imps.Contacts.CONTENT_URI, mContactId);
                mContentResolver.update(uriContact, values, null, null);

                //  insertMessageInDb(member.getName(), null, System.currentTimeMillis(),
                //      Imps.MessageType.PRESENCE_AVAILABLE);
            }
        }

        public void onMembersReset () {
            deleteAllGroupMembers();
        }

        public void onMemberJoined(ChatGroup group, final Contact contact) {
            insertGroupMemberInDb(contact);

            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onContactJoined(ChatSessionAdapter.this, contact);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        public void onMemberLeft(ChatGroup group, final Contact contact) {
            deleteGroupMemberInDb(contact);

            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onContactLeft(ChatSessionAdapter.this, contact);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        public void onError(ChatGroup group, final ImErrorInfo error) {
            // TODO: insert an error message?
            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onInviteError(ChatSessionAdapter.this, error);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        public void notifyChatSessionConverted() {
            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onConvertedToGroupChat(ChatSessionAdapter.this);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        @Override
        public void onIncomingReceipt(ChatSession ses, String id) {
            Imps.updateConfirmInDb(mContentResolver, mContactId, id, true);
        }

        @Override
        public void onMessagePostponed(ChatSession ses, String id) {
            updateMessageInDb(id, Imps.MessageType.QUEUED, -1);
        }

        @Override
        public void onReceiptsExpected(ChatSession ses, boolean isExpected) {
            // TODO

        }

        @Override
        public void sessionStatusChanged(SessionID sessionID) {

            if (sessionID.getRemoteUserId().equals(mChatSession.getParticipant().getAddress().getAddress()))
                onStatusChanged(mChatSession, OtrChatManager.getInstance().getSessionStatus(sessionID));


        }


        @Override
        public void onStatusChanged(ChatSession session, SessionStatus status) {
            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onStatusChanged(ChatSessionAdapter.this);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.   // TODO Auto-generated method stub
                }
            }
            mRemoteListeners.finishBroadcast();
            mDataHandler.onOtrStatusChanged(status);
            
            if (status == SessionStatus.ENCRYPTED)
            {
                sendPostponedMessages ();
            }

            mLastSessionStatus = status;
            
        }

        @Override
        public void onIncomingDataRequest(ChatSession session, org.awesomeapp.messenger.model.Message msg, byte[] value) {
            mDataHandler.onIncomingRequest(msg.getFrom(),msg.getTo(), value);
        }

        @Override
        public void onIncomingDataResponse(ChatSession session, org.awesomeapp.messenger.model.Message msg, byte[] value) {
            mDataHandler.onIncomingResponse(msg.getFrom(), msg.getTo(), value);
        }

        @Override
        public void onIncomingTransferRequest(final Transfer transfer) {

        }


    }

    class ChatConvertor implements GroupListener, GroupMemberListener {
        private ChatGroupManager mGroupMgr;
        private String mGroupName;

        public ChatConvertor() {
            mGroupMgr = mConnection.mGroupManager;
        }

        public void convertToGroupChat(String nickname) {
            mGroupMgr.addGroupListener(this);
            mGroupName = "G" + System.currentTimeMillis();
            try
            {
                mGroupMgr.createChatGroupAsync(mGroupName, nickname, nickname);
            }
            catch (Exception e){
                e.printStackTrace();
            }
        }

        public void onGroupCreated(ChatGroup group) {
            if (mGroupName.equalsIgnoreCase(group.getName())) {
                mGroupMgr.removeGroupListener(this);
                group.addMemberListener(this);
                mGroupMgr.inviteUserAsync(group, (Contact) mChatSession.getParticipant());
            }
        }

        public void onMembersReset ()
        {}

        public void onMemberJoined(ChatGroup group, Contact contact) {
            if (mChatSession.getParticipant().equals(contact)) {
                onConvertToGroupChatSuccess(group);
            }

            mContactStatusMap.put(contact.getName(), contact.getPresence().getStatus());
        }

        public void onSubjectChanged(ChatGroup group, String subject){}

        public void onGroupDeleted(ChatGroup group) {
        }

        public void onGroupError(int errorType, String groupName, ImErrorInfo error) {
        }

        public void onJoinedGroup(ChatGroup group) {
        }

        public void onLeftGroup(ChatGroup group) {
        }

        public void onError(ChatGroup group, ImErrorInfo error) {
        }

        public void onMemberLeft(ChatGroup group, Contact contact) {
            mContactStatusMap.remove(contact.getName());
        }
    }

    @Override
    public void setDataListener(IDataListener dataListener) throws RemoteException {

        mDataListener = dataListener;
        mDataHandler.setDataListener(mDataListener);
    }

    @Override
    public void setIncomingFileResponse (String transferForm, boolean acceptThis, boolean acceptAll)
    {

        mAcceptTransfer = acceptThis;
        mAcceptAllTransfer = acceptAll;
        mWaitingForResponse = false;

        mDataHandler.acceptTransfer(mLastFileUrl, transferForm);

    }

    class DataHandlerListenerImpl extends IDataListener.Stub {

        @Override
        public void onTransferComplete(boolean outgoing, String offerId, String from, String url, String mimeType, String filePath) {


            try {


                if (outgoing) {
                    Imps.updateConfirmInDb(service.getContentResolver(), mContactId, offerId, true);
                } else {

                    try
                    {
                        boolean isVerified = getDefaultOtrChatSession().isKeyVerified(from);

                        int type = isVerified ? Imps.MessageType.INCOMING_ENCRYPTED_VERIFIED : Imps.MessageType.INCOMING_ENCRYPTED;

                        insertOrUpdateChat(filePath);

                        Uri messageUri = Imps.insertMessageInDb(service.getContentResolver(),
                                mIsGroupChat, getId(),
                                true, from,
                                filePath, System.currentTimeMillis(), type,
                                0, offerId, mimeType);

                        int percent = (int)(100);

                        String[] path = url.split("/");
                        String sanitizedPath = SystemServices.sanitize(path[path.length - 1]);

                        final int N = mRemoteListeners.beginBroadcast();
                        for (int i = 0; i < N; i++) {
                            IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                            try {
                                listener.onIncomingFileTransferProgress(sanitizedPath, percent);
                            } catch (RemoteException e) {
                                // The RemoteCallbackList will take care of removing the
                                // dead listeners.
                            }
                        }
                        mRemoteListeners.finishBroadcast();

                        if (N == 0) {
                            String nickname = getNickName(from);

                            mStatusBarNotifier.notifyChat(mConnection.getProviderId(), mConnection.getAccountId(),
                                    getId(), from, nickname,service.getString(R.string.file_notify_text,mimeType,nickname) , false);
                        }
                    }
                    catch (Exception e)
                    {
                        Log.e(ImApp.LOG_TAG,"Error updating file transfer progress",e);
                    }

                }

                /**
                if (mimeType != null && mimeType.startsWith("audio"))
                {
                    MediaPlayer mp = new MediaPlayer();
                    try {
                        mp.setDataSource(file.getCanonicalPath());

                        mp.prepare();
                        mp.start();

                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        //e.printStackTrace();
                    }
                }*/

            } catch (Exception e) {
             //   mHandler.showAlert(service.getString(R.string.error_chat_file_transfer_title), service.getString(R.string.error_chat_file_transfer_body));
                OtrDebugLogger.log("error reading file", e);
            }


        }

        @Override
        public synchronized void onTransferFailed(boolean outgoing, String offerId, String from, String url, String reason) {


            String[] path = url.split("/");
            String sanitizedPath = SystemServices.sanitize(path[path.length - 1]);

            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onIncomingFileTransferError(sanitizedPath, reason);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        @Override
        public synchronized void onTransferProgress(boolean outgoing, String offerId, String from, String url, float percentF) {

            int percent = (int)(100*percentF);

            String[] path = url.split("/");
            String sanitizedPath = SystemServices.sanitize(path[path.length - 1]);

            try
            {
                final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < N; i++) {
                    IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                    try {
                        listener.onIncomingFileTransferProgress(sanitizedPath, percent);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
            }
            catch (Exception e)
            {
                Log.e(ImApp.LOG_TAG,"error broadcasting progress",e);
            }
            finally
            {
                mRemoteListeners.finishBroadcast();
            }
        }


        private String mLastTransferFrom;
        private String mLastTransferUrl;

        public void checkLastTransferRequest ()
        {
            if (mLastTransferFrom != null)
            {
                onTransferRequested(mLastTransferUrl,mLastTransferFrom,mLastTransferFrom,mLastTransferUrl);
                mLastTransferFrom = null;
                mLastTransferUrl = null;
            }
        }

        @Override
        public synchronized boolean onTransferRequested(String offerId, String from, String to, String transferUrl) {

            mAcceptTransfer = false;
            mWaitingForResponse = true;
            mLastFileUrl = transferUrl;

            if (mAcceptAllTransfer)
            {
                mAcceptTransfer = true;
                mWaitingForResponse = false;
                mLastTransferFrom = from;
                mLastTransferUrl = transferUrl;

                mDataHandler.acceptTransfer(mLastFileUrl, from);
            }
            else
            {
                try
                {
                    final int N = mRemoteListeners.beginBroadcast();

                    if (N > 0)
                    {
                        for (int i = 0; i < N; i++) {
                            IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                            try {
                                listener.onIncomingFileTransfer(from, transferUrl);
                            } catch (RemoteException e) {
                                // The RemoteCallbackList will take care of removing the
                                // dead listeners.
                            }
                        }
                    }
                    else
                    {
                        mLastTransferFrom = from;
                        mLastTransferUrl = transferUrl;
                        String nickname = getNickName(from);

                        //reinstated body display here in the notification; perhaps add preferences to turn that off
                        mStatusBarNotifier.notifyChat(mConnection.getProviderId(), mConnection.getAccountId(),
                                getId(), from, nickname, "Incoming file request", false);
                    }
                }
                finally
                {
                    mRemoteListeners.finishBroadcast();
                }

                mAcceptTransfer = false; //for now, wait for the user callback
            }

            return mAcceptTransfer;

        }



    }

    public boolean sendPushWhitelistToken(@NonNull String token) {
        if (mConnection.getState() == ImConnection.SUSPENDED) {
            // TODO Is it possible to postpone a TLV message? e.g: insertMessageInDb with type QUEUED
            return false;
        }

        // Whitelist tokens are intended for one recipient, for now
        if (isGroupChatSession())
            return false;

        org.awesomeapp.messenger.model.Message msg = new org.awesomeapp.messenger.model.Message("");

        msg.setFrom(mConnection.getLoginUser().getAddress());
        msg.setType(Imps.MessageType.OUTGOING);

        mChatSession.sendPushWhitelistTokenAsync(msg, new String[]{token});
        return true;
    }

    public synchronized void setContactTyping (Contact contact, boolean isTyping)
    {
        try {
            int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onContactTyping(ChatSessionAdapter.this, contact, isTyping);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }
        catch (IllegalStateException ise)
        {
            //sometimes this broadcast overlaps with others
        }
    }

    public void sendTypingStatus (boolean isTyping)
    {
       // mConnection.sendTypingStatus("fpp", isTyping);
    }

    public boolean isEncrypted ()
    {
        if (mChatSession.canOmemo())
        {
            return true;
        }
        else
        {
            IOtrChatSession otrChatSession = getDefaultOtrChatSession();
            if (otrChatSession != null)
            {
                try {

                    SessionStatus chatStatus = SessionStatus.values()[otrChatSession.getChatStatus()];

                    if (chatStatus == SessionStatus.ENCRYPTED) {
                        //boolean isVerified = otrChatSession.isKeyVerified(mChatSession.getParticipant().getAddress().toString());
                        // holder.mStatusIcon.setImageDrawable(getResources().getDrawable(R.drawable.ic_lock_outline_black_18dp));

                        return true;
                    }
                }
                catch (Exception e)
                {

                }
            }
        }

        return false;

    }


}
