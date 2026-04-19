package com.wakemate;

import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.videoio.VideoCapture;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;

public class MainApp extends JFrame {
    private JLabel videoLabel;
    private VideoCapture capture;
    private CascadeClassifier faceCascade;
    private CascadeClassifier eyeCascade;
    
    private boolean audioEnabled = true;
    private boolean redLightMode = false;
    private boolean isRunning = true;
    
    private Thread visionThread;
    private Thread audioThread;
    
    private long eyesClosedStartTime = 0;
    private final long ALARM_THRESHOLD = 1500; // 1.5 seconds
    private volatile boolean isAlarmPlaying = false;
    private SourceDataLine binauralLine;
    private SourceDataLine alarmLine;

    public MainApp() {
        super("WakeMate - Java Drowsiness Monitor");
        
        // Setup native OpenCV
        OpenCV.loadShared();
        
        // Load cascades
        String faceCascadePath = new File("src/main/resources/haarcascade_frontalface_alt.xml").getAbsolutePath();
        String eyeCascadePath = new File("src/main/resources/haarcascade_eye_tree_eyeglasses.xml").getAbsolutePath();
        
        faceCascade = new CascadeClassifier(faceCascadePath);
        eyeCascade = new CascadeClassifier(eyeCascadePath);
        
        if (faceCascade.empty() || eyeCascade.empty()) {
            System.err.println("Failed to load cascades!");
        }

        // Setup UI
        initUI();
        
        // Start Audio and Vision Threads
        startAudioSystem();
        startVisionSystem();
    }

    private void initUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLayout(new BorderLayout());
        getContentPane().setBackground(new Color(18, 18, 18)); // Dark mode
        
        // Video Panel
        videoLabel = new JLabel("", SwingConstants.CENTER);
        videoLabel.setOpaque(true);
        videoLabel.setBackground(new Color(30, 30, 30));
        add(videoLabel, BorderLayout.CENTER);
        
        // Controls Panel
        JPanel controls = new JPanel();
        controls.setBackground(new Color(24, 24, 24));
        controls.setLayout(new FlowLayout(FlowLayout.CENTER, 20, 10));
        
        JToggleButton btnAudio = new JToggleButton("Toggle Binaural Audio", true);
        btnAudio.setBackground(new Color(50, 50, 50));
        btnAudio.setForeground(Color.WHITE);
        btnAudio.setFocusPainted(false);
        btnAudio.addActionListener(e -> audioEnabled = btnAudio.isSelected());
        
        JToggleButton btnRedLight = new JToggleButton("Red Light Filter", false);
        btnRedLight.setBackground(new Color(50, 50, 50));
        btnRedLight.setForeground(Color.WHITE);
        btnRedLight.setFocusPainted(false);
        btnRedLight.addActionListener(e -> redLightMode = btnRedLight.isSelected());
        
        controls.add(btnAudio);
        controls.add(btnRedLight);
        
        add(controls, BorderLayout.SOUTH);
        
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                isRunning = false;
                if (capture != null) capture.release();
            }
        });
        
        setLocationRelativeTo(null);
    }
    
    private void startVisionSystem() {
        capture = new VideoCapture(0); // primary camera
        
        visionThread = new Thread(() -> {
            Mat frame = new Mat();
            while (isRunning) {
                if (capture.read(frame)) {
                    processFrame(frame);
                    
                    if (redLightMode) {
                        applyRedLightFilter(frame);
                    }
                    
                    BufferedImage img = matToBufferedImage(frame);
                    if (img != null) {
                        SwingUtilities.invokeLater(() -> {
                            videoLabel.setIcon(new ImageIcon(img));
                        });
                    }
                }
                try {
                    Thread.sleep(30); // ~30 fps
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        visionThread.start();
    }
    
    private void processFrame(Mat frame) {
        Mat grayFrame = new Mat();
        Imgproc.cvtColor(frame, grayFrame, Imgproc.COLOR_BGR2GRAY);
        Imgproc.equalizeHist(grayFrame, grayFrame);
        
        MatOfRect faces = new MatOfRect();
        faceCascade.detectMultiScale(grayFrame, faces);
        
        Rect[] facesArray = faces.toArray();
        if (facesArray.length > 0) {
            // Find biggest face
            Rect face = facesArray[0];
            for (Rect f : facesArray) {
                if (f.area() > face.area()) face = f;
            }
            
            // Draw rectangle on face
            Imgproc.rectangle(frame, new Point(face.x, face.y), 
                new Point(face.x + face.width, face.y + face.height), 
                new Scalar(255, 255, 0), 2);
                
            // Search for eyes in the face region
            Mat faceROI = grayFrame.submat(face);
            MatOfRect eyes = new MatOfRect();
            eyeCascade.detectMultiScale(faceROI, eyes);
            
            Rect[] eyesArray = eyes.toArray();
            
            if (eyesArray.length == 0) {
                // Eyes not detected - assume closed
                if (eyesClosedStartTime == 0) {
                    eyesClosedStartTime = System.currentTimeMillis();
                } else {
                    long closedDuration = System.currentTimeMillis() - eyesClosedStartTime;
                    if (closedDuration > ALARM_THRESHOLD) {
                        triggerAlarm();
                        // Draw warning text
                        Imgproc.putText(frame, "DROWSINESS DETECTED!", new Point(50, 50), 
                            Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, new Scalar(0, 0, 255), 3);
                    }
                }
            } else {
                // Eyes detected
                eyesClosedStartTime = 0;
                stopAlarm();
                
                // Draw eyes
                for (Rect eye : eyesArray) {
                    Point center = new Point(face.x + eye.x + eye.width * 0.5, face.y + eye.y + eye.height * 0.5);
                    int radius = (int) Math.round((eye.width + eye.height) * 0.25);
                    Imgproc.circle(frame, center, radius, new Scalar(0, 255, 0), 2);
                }
            }
        } else {
            // No face - reset
            eyesClosedStartTime = 0;
            stopAlarm();
        }
    }
    
    private void applyRedLightFilter(Mat frame) {
        Mat[] channels = new Mat[3];
        Core.split(frame, java.util.Arrays.asList(channels));
        // channels[0] = Blue, channels[1] = Green, channels[2] = Red
        channels[0].setTo(new Scalar(0)); // Disable Blue
        channels[1].setTo(new Scalar(0)); // Disable Green
        
        java.util.List<Mat> list = new java.util.ArrayList<>();
        list.add(channels[0]);
        list.add(channels[1]);
        list.add(channels[2]);
        Core.merge(list, frame);
    }
    
    private BufferedImage matToBufferedImage(Mat mat) {
        int type = BufferedImage.TYPE_BYTE_GRAY;
        if (mat.channels() > 1) {
            type = BufferedImage.TYPE_3BYTE_BGR;
        }
        int bufferSize = mat.channels() * mat.cols() * mat.rows();
        byte[] buffer = new byte[bufferSize];
        mat.get(0, 0, buffer); 

        BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), type);
        final byte[] targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(buffer, 0, targetPixels, 0, buffer.length);
        return image;
    }
    
    private void startAudioSystem() {
        audioThread = new Thread(() -> {
            try {
                // Audio format: 44.1kHz, 16 bit, Stereo
                AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                
                binauralLine = (SourceDataLine) AudioSystem.getLine(info);
                binauralLine.open(format, 44100);
                binauralLine.start();
                
                alarmLine = (SourceDataLine) AudioSystem.getLine(info);
                alarmLine.open(format, 44100);
                alarmLine.start();
                
                double sampleRate = 44100.0;
                double freqLeft = 200.0;
                double freqRight = 240.0; // 40Hz binaural beat
                double alarmFreq = 1000.0; // 1kHz sharp tone
                
                byte[] audioBuffer = new byte[4]; // 2 channels * 2 bytes
                
                long frameIndex = 0;
                
                while (isRunning) {
                    if (audioEnabled && !isAlarmPlaying) {
                        double time = frameIndex / sampleRate;
                        
                        // Left channel
                        short sampleLeft = (short) (Math.sin(2 * Math.PI * freqLeft * time) * 16383);
                        // Right channel
                        short sampleRight = (short) (Math.sin(2 * Math.PI * freqRight * time) * 16383);
                        
                        audioBuffer[0] = (byte) (sampleLeft & 0xFF);
                        audioBuffer[1] = (byte) ((sampleLeft >> 8) & 0xFF);
                        audioBuffer[2] = (byte) (sampleRight & 0xFF);
                        audioBuffer[3] = (byte) ((sampleRight >> 8) & 0xFF);
                        
                        binauralLine.write(audioBuffer, 0, 4);
                    } else if (isAlarmPlaying) {
                        // Square wave alarm
                        double time = frameIndex / sampleRate;
                        short alarmSample = (short) (Math.sin(2 * Math.PI * alarmFreq * time) > 0 ? 32000 : -32000);
                        
                        // Pulsing effect: 0.25s on, 0.25s off
                        if ((frameIndex % 22050) < 11025) { 
                            audioBuffer[0] = (byte) (alarmSample & 0xFF);
                            audioBuffer[1] = (byte) ((alarmSample >> 8) & 0xFF);
                            audioBuffer[2] = (byte) (alarmSample & 0xFF);
                            audioBuffer[3] = (byte) ((alarmSample >> 8) & 0xFF);
                        } else {
                            audioBuffer[0] = audioBuffer[1] = audioBuffer[2] = audioBuffer[3] = 0;
                        }
                        alarmLine.write(audioBuffer, 0, 4);
                    } else {
                        Thread.sleep(10);
                        frameIndex = 0;
                        continue;
                    }
                    frameIndex++;
                }
                
                binauralLine.drain();
                binauralLine.close();
                alarmLine.drain();
                alarmLine.close();
                
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        audioThread.start();
    }
    
    private void triggerAlarm() {
        isAlarmPlaying = true;
    }
    
    private void stopAlarm() {
        isAlarmPlaying = false;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new MainApp().setVisible(true);
        });
    }
}
