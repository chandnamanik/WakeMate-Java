import React, { useState, useEffect, useRef } from 'react';
import './App.css';
import { playAlarm, toggleBinauralBeats, getAudioContext } from './audio';

function App() {
  const [isRedLight, setIsRedLight] = useState(false);
  const [isAiActive, setIsAiActive] = useState(false);
  const [isDndMode, setIsDndMode] = useState(false);
  const [isAudioBeatsActive, setIsAudioBeatsActive] = useState(false);
  
  const [earValue, setEarValue] = useState('---');
  const [focusLevel, setFocusLevel] = useState('Standby');

  const videoRef = useRef(null);
  const canvasRef = useRef(null);
  const isAiActiveRef = useRef(isAiActive);
  const closedTimeRef = useRef(0);

  // Sync state to ref for access in MediaPipe callback
  useEffect(() => {
    isAiActiveRef.current = isAiActive;
  }, [isAiActive]);

  useEffect(() => {
    if (isRedLight) {
      document.body.classList.add('red-light-mode');
    } else {
      document.body.classList.remove('red-light-mode');
    }
  }, [isRedLight]);

  const toggleDnd = () => {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(err => {
        console.error(`Fullscreen failed: ${err.message}`);
      });
      setIsDndMode(true);
    } else {
      if (document.exitFullscreen) {
        document.exitFullscreen();
        setIsDndMode(false);
      }
    }
  };

  const handleBeatsToggle = () => {
    const newState = !isAudioBeatsActive;
    setIsAudioBeatsActive(newState);
    toggleBinauralBeats(newState);
  };

  const handleAlarmTest = () => {
    getAudioContext(); // Ensure context is unlocked if browser requires interaction
    playAlarm();
  };

  // MediaPipe FaceMesh Initialization
  useEffect(() => {
    if (!isAiActive) return;

    let camera = null;
    let faceMesh = null;

    // We must ensure the element is painted before attaching Camera
    const timer = setTimeout(() => {
      if (!videoRef.current || !canvasRef.current) return;
      if (!window.FaceMesh || !window.Camera) {
          console.error("MediaPipe scripts not loaded!");
          return;
      }

      faceMesh = new window.FaceMesh({
        locateFile: (file) => `https://cdn.jsdelivr.net/npm/@mediapipe/face_mesh/${file}`
      });

      faceMesh.setOptions({
        maxNumFaces: 1,
        refineLandmarks: true,
        minDetectionConfidence: 0.5,
        minTrackingConfidence: 0.5
      });

      faceMesh.onResults((results) => {
        if (!canvasRef.current || !videoRef.current) return;
        const canvasCtx = canvasRef.current.getContext('2d');
        const width = canvasRef.current.width;
        const height = canvasRef.current.height;

        canvasCtx.save();
        canvasCtx.clearRect(0, 0, width, height);
        
        // Draw video feed
        canvasCtx.drawImage(results.image, 0, 0, width, height);

        if (results.multiFaceLandmarks && results.multiFaceLandmarks.length > 0) {
          const landmarks = results.multiFaceLandmarks[0];
          
          // Helper to calculate Euclidean distance
          const dist = (p1, p2) => Math.hypot(p1.x - p2.x, p1.y - p2.y);
          
          // Eye Landmark approximations for EAR (Eye Aspect Ratio):
          // Left eye
          const leftEyeVert1 = dist(landmarks[160], landmarks[144]);
          const leftEyeVert2 = dist(landmarks[158], landmarks[153]);
          const leftEyeHoriz = dist(landmarks[33], landmarks[133]);
          const leftEAR = leftEyeHoriz > 0 ? (leftEyeVert1 + leftEyeVert2) / (2.0 * leftEyeHoriz) : 0;

          // Right eye
          const rightEyeVert1 = dist(landmarks[385], landmarks[380]);
          const rightEyeVert2 = dist(landmarks[387], landmarks[373]);
          const rightEyeHoriz = dist(landmarks[362], landmarks[263]);
          const rightEAR = rightEyeHoriz > 0 ? (rightEyeVert1 + rightEyeVert2) / (2.0 * rightEyeHoriz) : 0;

          const ear = (leftEAR + rightEAR) / 2.0;

          setEarValue(ear.toFixed(2));
          
          if (ear < 0.20) {
            if (closedTimeRef.current === 0) {
              closedTimeRef.current = Date.now();
            }
            if (Date.now() - closedTimeRef.current > 1500) { // 1.5s delay for true sleep
              setFocusLevel('ALERT: DROWSY');
              playAlarm(); 
            } else {
              setFocusLevel('Eyes Closed... Tracking');
            }
          } else {
            closedTimeRef.current = 0;
            setFocusLevel('Awake: Normal');
          }
          
          // Visual overlay: Draw landmarks points as small dots
          canvasCtx.fillStyle = '#3b82f6';
          for (const pt of landmarks) {
            canvasCtx.beginPath();
            canvasCtx.arc(pt.x * width, pt.y * height, 1, 0, 2 * Math.PI);
            canvasCtx.fill();
          }

        } else {
          setFocusLevel('No Face Detected');
          setEarValue('---');
        }
        canvasCtx.restore();
      });

      camera = new window.Camera(videoRef.current, {
        onFrame: async () => {
          if (isAiActiveRef.current && videoRef.current) {
            await faceMesh.send({ image: videoRef.current });
          }
        },
        width: 640,
        height: 480
      });

      camera.start().catch((e) => console.error(e));

    }, 100);

    return () => {
      clearTimeout(timer);
      if (camera) {
        camera.stop();
      }
      if (faceMesh) {
        faceMesh.close();
      }
    };
  }, [isAiActive]);

  return (
    <div className="app-container">
      <header className="glass-panel navbar">
        <h1 className="text-gradient">Wakemate</h1>
        <div className="status-badge">
          <div className={`status-dot ${isAiActive ? 'active' : 'inactive'}`}></div>
          <span>{isAiActive ? 'AI Monitoring Active' : 'System Standby'}</span>
        </div>
      </header>

      <main className="main-content">
        <div className="camera-section glass-panel">
          <div className="camera-feed-placeholder">
            {isAiActive ? (
              <>
                <video ref={videoRef} className="hidden-video" playsInline style={{ display: 'none' }}></video>
                <canvas ref={canvasRef} width="640" height="480" className="feed-canvas" style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: '18px' }}></canvas>
              </>
            ) : (
              <div className="feed-standby">
                <span className="camera-icon" role="img" aria-label="camera">📷</span>
                <p>Camera is currently offline</p>
                <button className="primary-btn" onClick={() => setIsAiActive(true)}>
                  Activate Camera
                </button>
              </div>
            )}
          </div>
          
          <div className="metrics-overlay">
            <div className="metric">
              <span className="label">Eye Aspect Ratio</span>
              <span className="value">{earValue}</span>
            </div>
            <div className="metric">
              <span className="label">Focus Level</span>
              <span className="value" style={{ color: focusLevel.includes('DROWSY') ? 'var(--danger-color)' : 'inherit' }}>
                {focusLevel}
              </span>
            </div>
          </div>
        </div>

        <aside className="control-panel glass-panel">
          <h2>Controls</h2>
          
          <div className="control-group">
            <div className="toggle-container">
              <span>Red Light Mode</span>
              <button 
                className={`toggle-btn ${isRedLight ? 'on' : 'off'}`} 
                onClick={() => setIsRedLight(!isRedLight)}
              >
                {isRedLight ? 'ON' : 'OFF'}
              </button>
            </div>
            <p className="control-desc">Preserves night vision during dark drives</p>
          </div>

          <div className="control-group">
            <div className="toggle-container">
              <span>Focus / DND Mode</span>
              <button 
                className={`toggle-btn ${isDndMode ? 'on' : 'off'}`} 
                onClick={toggleDnd}
              >
                {isDndMode ? 'ON' : 'OFF'}
              </button>
            </div>
            <p className="control-desc">Forces full screen execution</p>
          </div>

          <div className="control-group sound-controls">
            <h3>Audio Feedback</h3>
            <button className="secondary-btn" onClick={handleAlarmTest}>
              Test Alarm Beep
            </button>
            <button className={`secondary-btn ${isAudioBeatsActive ? 'active-beat' : ''}`} onClick={handleBeatsToggle} style={{ borderColor: isAudioBeatsActive ? 'var(--success-color)' : '' }}>
              {isAudioBeatsActive ? 'Stop Binaural Beats' : 'Start 40Hz Binaural Beats'}
            </button>
          </div>
        </aside>
      </main>
    </div>
  );
}

export default App;
