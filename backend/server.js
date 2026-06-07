const express = require('express');
const { createServer } = require('http');
const { Server } = require('socket.io');

const app = express();
const httpServer = createServer(app);
const io = new Server(httpServer, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  }
});

app.get('/', (req, res) => {
  res.send('Olyna Messenger WebRTC Signaling Server is UP and running. Use this URL in your Android App config!');
});

// Store active users and their sockets
const users = {}; // map of userId -> socketid

io.on('connection', (socket) => {
  console.log(`User connected: ${socket.id}`);

  // Register user id
  socket.on('register', (userId) => {
    users[userId] = socket.id;
    socket.userId = userId;
    console.log(`User ${userId} registered with socket ${socket.id}`);
    io.emit('all_users', Object.keys(users));
  });

  // Call request
  socket.on('call_request', ({ to, from, signalData, callType }) => {
    const targetSocketId = users[to];
    if (targetSocketId) {
      console.log(`Relaying call request from ${from} to ${to}`);
      io.to(targetSocketId).emit('call_incoming', {
        from,
        signalData,
        callType
      });
    } else {
      socket.emit('call_error', { message: `User ${to} is offline or not registered` });
    }
  });

  // Call acceptance
  socket.on('call_answered', ({ to, signalData }) => {
    const targetSocketId = users[to];
    if (targetSocketId) {
      console.log(`Relaying answered call event to ${to}`);
      io.to(targetSocketId).emit('call_connected', { signalData });
    }
  });

  // Ice candidates relay
  socket.on('ice_candidate', ({ to, candidate }) => {
    const targetSocketId = users[to];
    if (targetSocketId) {
      io.to(targetSocketId).emit('ice_candidate', { candidate });
    }
  });

  // Call terminates
  socket.on('call_ended', ({ to }) => {
    const targetSocketId = users[to];
    if (targetSocketId) {
      io.to(targetSocketId).emit('call_disconnected');
    }
  });

  // Offline handler
  socket.on('disconnect', () => {
    console.log(`User disconnected: ${socket.id}`);
    if (socket.userId) {
      delete users[socket.userId];
      io.emit('all_users', Object.keys(users));
    }
  });
});

const PORT = process.env.PORT || 3000;
httpServer.listen(PORT, () => {
  console.log(`Signaling server running on port ${PORT}`);
});
