let audioCtx = null;
let binauralNodes = [];

// Helper to initialize AudioContext interactively
export function getAudioContext() {
  if (!audioCtx) {
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  }
  if (audioCtx.state === 'suspended') {
    audioCtx.resume();
  }
  return audioCtx;
}

// Plays a rapid aggressive beep for alarms
export function playAlarm() {
  const ctx = getAudioContext();
  
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  
  osc.type = 'square'; // Aggressive alert tone
  osc.frequency.setValueAtTime(800, ctx.currentTime);
  osc.frequency.exponentialRampToValueAtTime(400, ctx.currentTime + 0.3);
  
  gain.gain.setValueAtTime(0, ctx.currentTime);
  gain.gain.linearRampToValueAtTime(1, ctx.currentTime + 0.05);
  gain.gain.linearRampToValueAtTime(0, ctx.currentTime + 0.5);
  
  osc.connect(gain);
  gain.connect(ctx.destination);
  
  osc.start();
  osc.stop(ctx.currentTime + 0.5);
}

// Generates 40Hz binaural beats (e.g. 200Hz left ear, 240Hz right ear)
export function toggleBinauralBeats(start) {
  const ctx = getAudioContext();
  
  if (!start) {
    binauralNodes.forEach(node => {
      try { node.stop(); } catch(e) {}
    });
    binauralNodes = [];
    return;
  }
  
  // Create nodes for Left and Right channels
  const oscL = ctx.createOscillator();
  const oscR = ctx.createOscillator();
  const merger = ctx.createChannelMerger(2);
  const gainL = ctx.createGain();
  const gainR = ctx.createGain();
  
  // Left Ear: 200Hz
  oscL.frequency.value = 200;
  gainL.gain.value = 0.5; // Tone down the volume
  oscL.connect(gainL);
  gainL.connect(merger, 0, 0); 
  
  // Right Ear: 240Hz (Creates a 40Hz difference)
  oscR.frequency.value = 240;
  gainR.gain.value = 0.5;
  oscR.connect(gainR);
  gainR.connect(merger, 0, 1);
  
  merger.connect(ctx.destination);
  
  oscL.start();
  oscR.start();
  
  binauralNodes = [oscL, oscR];
}
