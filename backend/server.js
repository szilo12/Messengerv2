import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import admin from 'firebase-admin';
import { readFileSync } from 'node:fs';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

function loadServiceAccount() {
  if (process.env.FIREBASE_SERVICE_ACCOUNT_FILE) {
    return JSON.parse(readFileSync(process.env.FIREBASE_SERVICE_ACCOUNT_FILE, 'utf8'));
  }

  if (process.env.FIREBASE_SERVICE_ACCOUNT_BASE64) {
    const json = Buffer.from(process.env.FIREBASE_SERVICE_ACCOUNT_BASE64, 'base64').toString('utf8');
    return JSON.parse(json);
  }

  if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
    return JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
  }

  return {};
}

const serviceAccount = loadServiceAccount();

if (!serviceAccount.project_id || !serviceAccount.client_email || !serviceAccount.private_key) {
  throw new Error(
    'Missing Firebase service account. Add FIREBASE_SERVICE_ACCOUNT_FILE, FIREBASE_SERVICE_ACCOUNT_BASE64 or FIREBASE_SERVICE_ACCOUNT_JSON to backend environment variables.'
  );
}

admin.initializeApp({
  credential: admin.credential.cert({
    ...serviceAccount,
    private_key: String(serviceAccount.private_key).replace(/\\n/g, '\n').trim(),
  }),
});

const db = process.env.FIRESTORE_DATABASE_ID
  ? getFirestore(admin.app(), process.env.FIRESTORE_DATABASE_ID)
  : getFirestore();

const app = express();
app.set('trust proxy', true);

const allowedOrigins = (process.env.CORS_ORIGIN || '*')
  .split(',')
  .map((origin) => origin.trim())
  .filter(Boolean);

app.use(
  cors({
    origin: allowedOrigins.includes('*') ? true : allowedOrigins,
    credentials: true,
  })
);

app.use(express.json({ limit: '256kb' }));

app.get('/', (_req, res) => {
  res.json({
    ok: true,
    message: 'Messenger push backend is running',
    service: 'messenger-push-backend',
    mode: 'FCM data-only push',
    endpoints: {
      health: '/health',
      sendMessageNotification: '/send-message-notification',
      sendCallNotification: '/send-call-notification',
      sendCallStatusNotification: '/send-call-status-notification',
      endCallBg: '/end-call-bg',
    },
  });
});

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    status: 'healthy',
    service: 'messenger-push-backend',
    mode: 'FCM data-only push',
  });
});

async function requireFirebaseUser(req, res, next) {
  try {
    const header = req.header('Authorization') || '';
    const match = header.match(/^Bearer (.+)$/);

    if (!match) {
      return res.status(401).json({
        ok: false,
        error: 'Missing Authorization bearer token.',
      });
    }

    req.user = await admin.auth().verifyIdToken(match[1], true);
    return next();
  } catch (error) {
    console.error('Auth verification failed:', error);
    return res.status(401).json({
      ok: false,
      error: 'Invalid or expired Firebase Auth ID token.',
    });
  }
}

function shortMessage(text) {
  const normalized = String(text || '').replace(/\s+/g, ' ').trim();
  return normalized.length > 120 ? `${normalized.slice(0, 117)}...` : normalized;
}

function getTokenEntriesFromUserData(userData) {
  const tokenMap = userData?.fcmTokens || {};

  if (Array.isPlainObject ? false : Array.isArray(tokenMap)) {
    return tokenMap
      .filter((entry) => entry?.token)
      .map((entry, index) => ({
        tokenId: `legacy_${index}`,
        token: String(entry.token),
        source: 'map',
      }));
  }

  return Object.entries(tokenMap)
    .filter(([, entry]) => entry?.token)
    .map(([tokenId, entry]) => ({
      tokenId,
      token: String(entry.token),
      source: 'map',
    }));
}

async function getTokenEntriesFromSubcollection(receiverRef) {
  const snap = await receiverRef.collection('fcmTokens').get();
  return snap.docs
    .map((docSnap) => ({
      tokenId: docSnap.id,
      token: String(docSnap.data()?.token || ''),
      source: 'subcollection',
      ref: docSnap.ref,
    }))
    .filter((entry) => entry.token);
}

function uniqueTokenEntries(entries) {
  const seen = new Set();
  const unique = [];

  for (const entry of entries) {
    if (!entry.token || seen.has(entry.token)) continue;
    seen.add(entry.token);
    unique.push(entry);
  }

  return unique;
}

async function sendCallStatusPushToUser(userId, callId, chatId, status) {
  if (!userId) return { tokenCount: 0, successCount: 0, failureCount: 0 };

  const receiverRef = db.collection('users').doc(String(userId));
  const receiverSnap = await receiverRef.get();
  if (!receiverSnap.exists) return { tokenCount: 0, successCount: 0, failureCount: 0 };

  const mapTokenEntries = getTokenEntriesFromUserData(receiverSnap.data());
  const subcollectionTokenEntries = await getTokenEntriesFromSubcollection(receiverRef);
  const tokenEntries = uniqueTokenEntries([...mapTokenEntries, ...subcollectionTokenEntries]);
  const tokens = tokenEntries.map((entry) => entry.token);
  if (tokens.length === 0) return { tokenCount: 0, successCount: 0, failureCount: 0 };

  const response = await admin.messaging().sendEachForMulticast({
    tokens,
    data: {
      type: 'call',
      callId: String(callId),
      chatId: String(chatId),
      status: String(status),
    },
    android: {
      priority: 'high',
      ttl: 60 * 60 * 1000,
    },
  });

  const mapDeletes = {};
  const subcollectionDeletes = [];

  response.responses.forEach((result, index) => {
    if (result.success) return;

    const code = result.error?.code || '';
    const entry = tokenEntries[index];
    if (
      code === 'messaging/invalid-registration-token' ||
      code === 'messaging/registration-token-not-registered'
    ) {
      if (entry.source === 'map') {
        mapDeletes[`fcmTokens.${entry.tokenId}`] = FieldValue.delete();
      }
      if (entry.source === 'subcollection' && entry.ref) {
        subcollectionDeletes.push(entry.ref.delete());
      }
    }
  });

  if (Object.keys(mapDeletes).length > 0) {
    await receiverRef.update(mapDeletes);
  }
  if (subcollectionDeletes.length > 0) {
    await Promise.allSettled(subcollectionDeletes);
  }

  return {
    tokenCount: tokens.length,
    successCount: response.successCount,
    failureCount: response.failureCount,
    removedTokenCount: Object.keys(mapDeletes).length + subcollectionDeletes.length,
  };
}

function getPublicBaseUrl(req) {
  if (process.env.PUBLIC_BASE_URL) {
    return String(process.env.PUBLIC_BASE_URL).replace(/\/$/, '');
  }

  const proto = String(req.headers['x-forwarded-proto'] || req.protocol || 'https').split(',')[0].trim();
  return `${proto}://${req.get('host')}`;
}

function normalizePhotoURL(value) {
  return String(value || '').trim();
}

function buildPushAvatarUrl(photoURL, senderId, req) {
  const photo = normalizePhotoURL(photoURL);

  if (!photo) return '';

  if (photo.startsWith('data:image')) {
    return `${getPublicBaseUrl(req)}/avatar/${encodeURIComponent(senderId)}`;
  }

  if (photo.startsWith('http://') || photo.startsWith('https://')) {
    return photo;
  }

  if (photo.startsWith('preset:')) {
    return photo;
  }

  return '';
}

async function getSenderProfile(senderId) {
  try {
    const snap = await db.collection('users').doc(String(senderId)).get();
    return snap.exists ? snap.data() : null;
  } catch (error) {
    console.warn('Could not read sender profile:', senderId, error?.message || error);
    return null;
  }
}

app.get('/avatar/:uid', async (req, res) => {
  try {
    const uid = String(req.params.uid || '').trim();
    if (!uid) {
      return res.status(400).send('Missing uid');
    }

    const snap = await db.collection('users').doc(uid).get();
    if (!snap.exists) {
      return res.status(404).send('Avatar user not found');
    }

    const profile = snap.data() || {};
    const photoURL = normalizePhotoURL(profile.photoURL);

    if (photoURL.startsWith('http://') || photoURL.startsWith('https://')) {
      return res.redirect(302, photoURL);
    }

    if (!photoURL.startsWith('data:image')) {
      return res.status(204).end();
    }

    const match = photoURL.match(/^data:(image\/[a-zA-Z0-9.+-]+);base64,(.+)$/);
    if (!match) {
      return res.status(415).send('Unsupported avatar data URI');
    }

    const contentType = match[1];
    const buffer = Buffer.from(match[2], 'base64');

    res.setHeader('Content-Type', contentType);
    res.setHeader('Cache-Control', 'public, max-age=3600');
    return res.send(buffer);
  } catch (error) {
    console.error('Avatar endpoint failed:', error);
    return res.status(500).send('Avatar endpoint failed');
  }
});

app.post('/send-message-notification', requireFirebaseUser, async (req, res) => {
  try {
    const senderId = req.user.uid;
    const { receiverId, senderName, messageText, chatId, avatarUrl } = req.body || {};

    if (!receiverId || !senderName || !messageText || !chatId) {
      return res.status(400).json({
        ok: false,
        error: 'receiverId, senderName, messageText and chatId are required.',
      });
    }

    if (receiverId === senderId) {
      return res.status(400).json({
        ok: false,
        error: 'Cannot send a notification to yourself.',
      });
    }

    const receiverRef = db.collection('users').doc(String(receiverId));
    const receiverSnap = await receiverRef.get();

    if (!receiverSnap.exists) {
      console.warn('Receiver user document not found:', receiverId);
      return res.status(404).json({
        ok: false,
        error: 'Receiver user document not found.',
      });
    }

    const mapTokenEntries = getTokenEntriesFromUserData(receiverSnap.data());
    const subcollectionTokenEntries = await getTokenEntriesFromSubcollection(receiverRef);
    const tokenEntries = uniqueTokenEntries([...mapTokenEntries, ...subcollectionTokenEntries]);
    const tokens = tokenEntries.map((entry) => entry.token);

    if (tokens.length === 0) {
      console.warn('Receiver has no FCM tokens:', receiverId);
      return res.json({
        ok: true,
        successCount: 0,
        failureCount: 0,
        removedTokenCount: 0,
        tokenSourceCounts: {
          map: mapTokenEntries.length,
          subcollection: subcollectionTokenEntries.length,
        },
        message: 'Receiver has no FCM tokens.',
      });
    }

    const senderProfile = await getSenderProfile(senderId);
    const senderPhotoURL = normalizePhotoURL(senderProfile?.photoURL || avatarUrl || '');
    const pushAvatarUrl = buildPushAvatarUrl(senderPhotoURL, senderId, req);

    const body = shortMessage(messageText);
    const title = String(senderName || senderProfile?.displayName || 'Új üzenet');

    const response = await admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        type: 'chat_message',
        chatId: String(chatId),
        senderId: String(senderId),
        senderName: title,
        messageText: body,
        avatarUrl: pushAvatarUrl,
      },
      android: {
        priority: 'high',
        ttl: 60 * 60 * 1000,
        collapseKey: `chat_${String(chatId).slice(0, 32)}`,
      },
    });

    const mapDeletes = {};
    const subcollectionDeletes = [];

    response.responses.forEach((result, index) => {
      if (result.success) return;

      const code = result.error?.code || '';
      const entry = tokenEntries[index];

      if (
        code === 'messaging/invalid-registration-token' ||
        code === 'messaging/registration-token-not-registered'
      ) {
        if (entry.source === 'map') {
          mapDeletes[`fcmTokens.${entry.tokenId}`] = FieldValue.delete();
        }
        if (entry.source === 'subcollection' && entry.ref) {
          subcollectionDeletes.push(entry.ref.delete());
        }
      }

      console.error('FCM send error:', {
        tokenIndex: index,
        tokenSource: entry?.source,
        tokenId: entry?.tokenId,
        code,
        message: result.error?.message,
      });
    });

    if (Object.keys(mapDeletes).length > 0) {
      await receiverRef.update(mapDeletes);
    }
    if (subcollectionDeletes.length > 0) {
      await Promise.allSettled(subcollectionDeletes);
    }

    console.log('FCM notification result:', {
      receiverId,
      successCount: response.successCount,
      failureCount: response.failureCount,
      tokenCount: tokens.length,
      mapTokenCount: mapTokenEntries.length,
      subcollectionTokenCount: subcollectionTokenEntries.length,
      removedTokenCount: Object.keys(mapDeletes).length + subcollectionDeletes.length,
    });

    return res.json({
      ok: true,
      successCount: response.successCount,
      failureCount: response.failureCount,
      tokenCount: tokens.length,
      tokenSourceCounts: {
        map: mapTokenEntries.length,
        subcollection: subcollectionTokenEntries.length,
      },
      removedTokenCount: Object.keys(mapDeletes).length + subcollectionDeletes.length,
    });
  } catch (error) {
    console.error('Notification send failed:', error);

    return res.status(500).json({
      ok: false,
      error: 'Notification send failed.',
      details: process.env.NODE_ENV === 'production' ? undefined : String(error?.message || error),
    });
  }
});

app.post('/send-call-notification', requireFirebaseUser, async (req, res) => {
  try {
    const callerId = req.user.uid;
    const { receiverId, callerName, callId, chatId, callType, avatarUrl } = req.body || {};

    if (!receiverId || !callerName || !callId || !chatId || !callType) {
      return res.status(400).json({
        ok: false,
        error: 'receiverId, callerName, callId, chatId and callType are required.',
      });
    }

    if (receiverId === callerId) {
      return res.status(400).json({
        ok: false,
        error: 'Cannot send a call notification to yourself.',
      });
    }

    const receiverRef = db.collection('users').doc(String(receiverId));
    const receiverSnap = await receiverRef.get();

    if (!receiverSnap.exists) {
      return res.status(404).json({
        ok: false,
        error: 'Receiver user document not found.',
      });
    }

    const mapTokenEntries = getTokenEntriesFromUserData(receiverSnap.data());
    const subcollectionTokenEntries = await getTokenEntriesFromSubcollection(receiverRef);
    const tokenEntries = uniqueTokenEntries([...mapTokenEntries, ...subcollectionTokenEntries]);
    const tokens = tokenEntries.map((entry) => entry.token);

    if (tokens.length === 0) {
      console.warn('Receiver has no FCM tokens for call:', receiverId);
      return res.json({
        ok: true,
        successCount: 0,
        failureCount: 0,
        removedTokenCount: 0,
        message: 'Receiver has no FCM tokens.',
      });
    }

    const callerProfile = await getSenderProfile(callerId);
    const callerPhotoURL = normalizePhotoURL(callerProfile?.photoURL || avatarUrl || '');
    const pushAvatarUrl = buildPushAvatarUrl(callerPhotoURL, callerId, req);
    const backendUrl = process.env.PUBLIC_BACKEND_URL || `${req.protocol}://${req.get('host')}`;

    const response = await admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        type: 'call',
        callId: String(callId),
        chatId: String(chatId),
        senderId: String(callerId),
        senderName: String(callerName || 'Messenger hívás'),
        callerName: String(callerName || 'Messenger hívás'),
        callType: String(callType),
        avatarUrl: pushAvatarUrl,
        backendUrl: String(backendUrl),
        messageText: String(callType) === 'video' ? 'Bejövő videohívás' : 'Bejövő hanghívás',
      },
      android: {
        priority: 'high',
        ttl: 60 * 60 * 1000,
        directBootOk: true,
      },
    });

    const mapDeletes = {};
    const subcollectionDeletes = [];

    response.responses.forEach((result, index) => {
      if (result.success) return;

      const code = result.error?.code || '';
      const entry = tokenEntries[index];

      if (
        code === 'messaging/invalid-registration-token' ||
        code === 'messaging/registration-token-not-registered'
      ) {
        if (entry.source === 'map') {
          mapDeletes[`fcmTokens.${entry.tokenId}`] = FieldValue.delete();
        }
        if (entry.source === 'subcollection' && entry.ref) {
          subcollectionDeletes.push(entry.ref.delete());
        }
      }

      console.error('FCM call send error:', {
        tokenIndex: index,
        tokenSource: entry?.source,
        tokenId: entry?.tokenId,
        code,
        message: result.error?.message,
      });
    });

    if (Object.keys(mapDeletes).length > 0) {
      await receiverRef.update(mapDeletes);
    }
    if (subcollectionDeletes.length > 0) {
      await Promise.allSettled(subcollectionDeletes);
    }

    console.log('FCM call notification result:', {
      receiverId,
      tokenCount: tokens.length,
      successCount: response.successCount,
      failureCount: response.failureCount,
      removedTokenCount: Object.keys(mapDeletes).length + subcollectionDeletes.length,
    });

    return res.json({
      ok: true,
      tokenCount: tokens.length,
      successCount: response.successCount,
      failureCount: response.failureCount,
      removedTokenCount: Object.keys(mapDeletes).length + subcollectionDeletes.length,
    });
  } catch (error) {
    console.error('Call notification send failed:', error);

    return res.status(500).json({
      ok: false,
      error: 'Call notification send failed.',
      details: process.env.NODE_ENV === 'production' ? undefined : String(error?.message || error),
    });
  }
});

app.post('/decline-call-bg', async (req, res) => {
  try {
    const { callId } = req.body || {};

    if (!callId) {
      return res.status(400).json({
        ok: false,
        error: 'callId is required.',
      });
    }

    const callRef = db.collection('calls').doc(String(callId));
    const callSnap = await callRef.get();

    if (!callSnap.exists) {
      return res.status(404).json({
        ok: false,
        error: 'Call document not found.',
      });
    }

    const callData = callSnap.data();
    if (callData.status !== 'ringing') {
      return res.json({
        ok: true,
        message: 'Call is not in ringing state, skipping update.',
      });
    }

    // Update status to declined
    await callRef.update({
      status: 'declined',
      updatedAt: FieldValue.serverTimestamp(),
    });

    // Write call history log to messages
    const logMessageId = `call_log_${callId}`;
    const msgRef = db
      .collection('chats')
      .doc(callData.chatId)
      .collection('messages')
      .doc(logMessageId);
    const displayedText = callData.type === 'video' ? 'Kihagyott videohívás' : 'Kihagyott hanghívás';

    await msgRef.set({
      id: logMessageId,
      senderId: callData.callerId,
      text: displayedText,
      createdAt: FieldValue.serverTimestamp(),
      status: 'seen',
      isCallLog: true,
      callType: callData.type,
      callDuration: 0,
      callStatus: 'declined',
    });

    // Update last message in chat session
    const chatRef = db.collection('chats').doc(callData.chatId);
    await chatRef.update({
      lastMessageText: displayedText,
      lastMessageSenderId: callData.callerId,
      lastMessageAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });

    // Send status notification to caller
    const callerId = callData.callerId;
    const receiverRef = db.collection('users').doc(String(callerId));
    const receiverSnap = await receiverRef.get();

    if (receiverSnap.exists) {
      const mapTokenEntries = getTokenEntriesFromUserData(receiverSnap.data());
      const subcollectionTokenEntries = await getTokenEntriesFromSubcollection(receiverRef);
      const tokenEntries = uniqueTokenEntries([...mapTokenEntries, ...subcollectionTokenEntries]);
      const tokens = tokenEntries.map((entry) => entry.token);

      if (tokens.length > 0) {
        await admin.messaging().sendEachForMulticast({
          tokens,
          data: {
            type: 'call',
            callId: String(callId),
            chatId: String(callData.chatId),
            status: 'declined',
          },
          android: {
            priority: 'high',
            ttl: 60 * 60 * 1000,
          },
        });
      }
    }

    // Also stop ringing on the receiver's other logged-in devices.
    await sendCallStatusPushToUser(callData.receiverId, callId, callData.chatId, 'declined').catch((error) => {
      console.warn('Could not send declined status to receiver devices:', error?.message || error);
    });

    return res.json({
      ok: true,
      message: 'Call declined successfully.',
    });
  } catch (error) {
    console.error('decline-call-bg failed:', error);
    return res.status(500).json({
      ok: false,
      error: 'Decline call failed.',
    });
  }
});

app.post('/accept-call-bg', async (req, res) => {
  try {
    const { callId } = req.body || {};

    if (!callId) {
      return res.status(400).json({
        ok: false,
        error: 'callId is required.',
      });
    }

    const callRef = db.collection('calls').doc(String(callId));
    const callSnap = await callRef.get();

    if (!callSnap.exists) {
      return res.status(404).json({
        ok: false,
        error: 'Call document not found.',
      });
    }

    const callData = callSnap.data();
    if (callData.status !== 'ringing' && callData.status !== 'accepted') {
      return res.json({
        ok: true,
        message: 'Call is no longer ringing, skipping accept update.',
      });
    }

    await callRef.update({
      status: 'accepted',
      acceptedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });

    const callerId = callData.callerId;
    const receiverRef = db.collection('users').doc(String(callerId));
    const receiverSnap = await receiverRef.get();

    if (receiverSnap.exists) {
      const mapTokenEntries = getTokenEntriesFromUserData(receiverSnap.data());
      const subcollectionTokenEntries = await getTokenEntriesFromSubcollection(receiverRef);
      const tokenEntries = uniqueTokenEntries([...mapTokenEntries, ...subcollectionTokenEntries]);
      const tokens = tokenEntries.map((entry) => entry.token);

      if (tokens.length > 0) {
        await admin.messaging().sendEachForMulticast({
          tokens,
          data: {
            type: 'call',
            callId: String(callId),
            chatId: String(callData.chatId),
            status: 'accepted',
          },
          android: {
            priority: 'high',
            ttl: 60 * 60 * 1000,
          },
        });
      }
    }

    // Also stop ringing on the receiver's other logged-in devices.
    await sendCallStatusPushToUser(callData.receiverId, callId, callData.chatId, 'accepted').catch((error) => {
      console.warn('Could not send accepted status to receiver devices:', error?.message || error);
    });

    return res.json({
      ok: true,
      message: 'Call accepted successfully.',
    });
  } catch (error) {
    console.error('accept-call-bg failed:', error);
    return res.status(500).json({
      ok: false,
      error: 'Accept call failed.',
    });
  }
});

app.post('/end-call-bg', async (req, res) => {
  try {
    const { callId } = req.body || {};

    if (!callId) {
      return res.status(400).json({
        ok: false,
        error: 'callId is required.',
      });
    }

    const callRef = db.collection('calls').doc(String(callId));
    const callSnap = await callRef.get();

    if (!callSnap.exists) {
      return res.status(404).json({
        ok: false,
        error: 'Call document not found.',
      });
    }

    const callData = callSnap.data();
    if (['ended', 'declined', 'cancelled', 'missed'].includes(callData.status)) {
      return res.json({
        ok: true,
        message: 'Call is already finished.',
      });
    }

    const nextStatus = callData.status === 'accepted' ? 'ended' : 'cancelled';

    await callRef.update({
      status: nextStatus,
      updatedAt: FieldValue.serverTimestamp(),
    });

    await Promise.allSettled([
      sendCallStatusPushToUser(callData.callerId, callId, callData.chatId, nextStatus),
      sendCallStatusPushToUser(callData.receiverId, callId, callData.chatId, nextStatus),
    ]);

    return res.json({
      ok: true,
      message: 'Call ended successfully.',
      status: nextStatus,
    });
  } catch (error) {
    console.error('end-call-bg failed:', error);
    return res.status(500).json({
      ok: false,
      error: 'End call failed.',
    });
  }
});

app.post('/send-call-status-notification', requireFirebaseUser, async (req, res) => {
  try {
    const senderId = req.user.uid;
    const { receiverId, callId, chatId, status } = req.body || {};

    if (!receiverId || !callId || !chatId || !status) {
      return res.status(400).json({
        ok: false,
        error: 'receiverId, callId, chatId and status are required.',
      });
    }

    const receiverRef = db.collection('users').doc(String(receiverId));
    const receiverSnap = await receiverRef.get();

    if (!receiverSnap.exists) {
      return res.status(404).json({
        ok: false,
        error: 'Receiver user document not found.',
      });
    }

    const mapTokenEntries = getTokenEntriesFromUserData(receiverSnap.data());
    const subcollectionTokenEntries = await getTokenEntriesFromSubcollection(receiverRef);
    const tokenEntries = uniqueTokenEntries([...mapTokenEntries, ...subcollectionTokenEntries]);
    const tokens = tokenEntries.map((entry) => entry.token);

    if (tokens.length === 0) {
      console.warn('Receiver has no FCM tokens for call status:', receiverId);
      return res.json({
        ok: true,
        successCount: 0,
        failureCount: 0,
        removedTokenCount: 0,
        message: 'Receiver has no FCM tokens.',
      });
    }

    const response = await admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        type: 'call',
        callId: String(callId),
        chatId: String(chatId),
        status: String(status),
      },
      android: {
        priority: 'high',
        ttl: 60 * 60 * 1000,
      },
    });

    const mapDeletes = {};
    const subcollectionDeletes = [];

    response.responses.forEach((result, index) => {
      if (result.success) return;

      const code = result.error?.code || '';
      const entry = tokenEntries[index];

      if (
        code === 'messaging/invalid-registration-token' ||
        code === 'messaging/registration-token-not-registered'
      ) {
        if (entry.source === 'map') {
          mapDeletes[`fcmTokens.${entry.tokenId}`] = FieldValue.delete();
        }
        if (entry.source === 'subcollection' && entry.ref) {
          subcollectionDeletes.push(entry.ref.delete());
        }
      }
    });

    if (Object.keys(mapDeletes).length > 0) {
      await receiverRef.update(mapDeletes);
    }
    if (subcollectionDeletes.length > 0) {
      await Promise.allSettled(subcollectionDeletes);
    }

    return res.json({
      ok: true,
      tokenCount: tokens.length,
      successCount: response.successCount,
      failureCount: response.failureCount,
      removedTokenCount: Object.keys(mapDeletes).length + subcollectionDeletes.length,
    });
  } catch (error) {
    console.error('Call status notification send failed:', error);
    return res.status(500).json({
      ok: false,
      error: 'Call status notification send failed.',
    });
  }
});

app.use((_req, res) => {
  res.status(404).json({
    ok: false,
    error: 'Route not found.',
  });
});

const port = Number(process.env.PORT || 8080);

app.listen(port, '0.0.0.0', () => {
  console.log(`Messenger push backend listening on port ${port}`);
});
