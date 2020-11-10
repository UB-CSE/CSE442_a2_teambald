package com.teambald.cse442_project_team_bald.Service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.teambald.cse442_project_team_bald.Fragments.HomeFragment;
import com.teambald.cse442_project_team_bald.Fragments.RecordingListFragment;
import com.teambald.cse442_project_team_bald.Fragments.SettingFragment;
import com.teambald.cse442_project_team_bald.MainActivity;
import com.teambald.cse442_project_team_bald.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/*
 * Use Service and thread to run long time lasting recording.
 */
public class RecordingService extends Service {
    public static final String CHANNEL_ID = "Recording_Service_Channel_01";
    private static final String TAG = "RecordingServiceTAG";
    private MediaRecorder mediaRecorder;
    private String recordFile;
    public String recordPath;

    private HandlerThread mRecordingThread;
    private Handler mRecordingHandler;
    //saved recording length.
    private int recordingLength;
    private SharedPreferences prefs;

    private StorageReference mStorageRef;
    private static final String durationMetaDataConst = "Duration";

    @Override
    public void onCreate() {
        //Update and Create Local Recording List and Cloud Recording List
        String rawPath = getApplicationContext().getExternalFilesDir("/").getAbsolutePath();
        recordPath = rawPath+File.separator+"LocalRecording";


        Log.d(TAG, "Service is created");
        mRecordingThread = new HandlerThread("Recording thread", Thread.MAX_PRIORITY);
        mRecordingThread.start();
        mRecordingHandler = new Handler(mRecordingThread.getLooper());

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        mStorageRef = FirebaseStorage.getInstance().getReference();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String signal = intent.getStringExtra("Recording_Service_Signal");
        Log.i(TAG, "Received " + signal);
        if (signal.equals("stop")) {
            Log.i(TAG, "Received Stop Foreground Intent");
            //Stop recording.
            stopRecording();
        }else {
            //Start recording.
            startRecording();

            createNotificationChannel();
            Log.i(TAG, "Received start id " + startId + ": " + intent);

            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this,
                    0, notificationIntent, 0);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("SmartRecorder")
                    .setContentText("SmartRecorder is recording audio.")
                    .setSmallIcon(R.drawable.ic_mic_24)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setWhen(System.currentTimeMillis())
                    .build();
            startForeground(2001, notification);
        }
        return START_STICKY;
    }

    private void startRecording() {
        //Read set recording length, default is 5 mins.
        recordingLength = prefs.getInt(getString(R.string.recording_length_key), 5);
        Log.i(TAG, "Recording will be saved every " + recordingLength + "mins");
        //Get current date and time
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy_MM_dd_hh_mm_ss", Locale.US);
        Date now = new Date();
        //initialize filename variable with date and time at the end to ensure the new file wont overwrite previous file
        recordFile = "Recording_"+formatter.format(now)+ ".mp4";

        //Path used for encryption.
        final String filePath = recordPath + "/" + recordFile;

        //Setup Media Recorder for recording
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(filePath);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

        //Save recording periodically.
        mediaRecorder.setMaxDuration(recordingLength * 60 * 1000);
        //Will be executed when reach max duration.
        mediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
            @Override
            public void onInfo(MediaRecorder mediaRecorder, int i, int i1) {
                //When reach max duration, stop, save the file and start again.
                if (i == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    //Stop and save the audio.
                    mediaRecorder.stop();
                    mediaRecorder.reset();
                    mediaRecorder.release();

                    //Auto upload to Firebase Storage for signed-in user.
                    final String fireBaseFolder = prefs.getString(SettingFragment.LogInEmail,null);
                    if(fireBaseFolder != null) {
                        final String duration = recordingLength < 10 ? ("0" + recordingLength + ":00") : (recordingLength + ":00");
                        uploadRecording(recordPath, recordFile, fireBaseFolder, duration);
                    }

                    //Restart the recorder.
                    startRecording();
                }

            }
        });

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //Start Recording
        mediaRecorder.start();

    }


    private void stopRecording() {
        Log.i(TAG, "Stop recording");
        //Change text on page to file saved
        //Stop media recorder and set it to null for further use to record new audio
        if(mediaRecorder == null){
            return;
        }
        mediaRecorder.stop();
        mediaRecorder.reset();
        mediaRecorder.release();
        mediaRecorder = null;

        //Auto upload to Firebase Storage for signed-in user.
        final String fireBaseFolder = prefs.getString(SettingFragment.LogInEmail,null);
        Log.i(TAG, "firebaseFolder = " + fireBaseFolder);
        if(fireBaseFolder != null) {
            uploadRecording(recordPath, recordFile, fireBaseFolder, readRecentRecordingLength());
        }

        //Show toast to notify user that the file has been saved.
        Toast toast = Toast.makeText(getApplicationContext(), "Recording has been saved.", Toast.LENGTH_SHORT);
        toast.show();

        //Interrupt thread.
        mRecordingThread.quitSafely();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service has been destroyed");
        mRecordingThread.quitSafely();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sensor Service Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            serviceChannel.enableVibration(false);
            serviceChannel.setSound(null,null);
            serviceChannel.enableLights(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    void uploadRecording(String path, final String filename, final String fireBaseFolder, String duration){
        final String fullPath = path + "/" + filename;
        final String fullFBPath = fireBaseFolder + "/" + filename;

        Log.i(TAG, "Trying uploadRecording");

        Uri file = Uri.fromFile(new File(fullPath));
        StorageReference storageReference = mStorageRef.child(fireBaseFolder).child(filename);
        storageReference.putFile(file)
                .addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
                    @Override
                    public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                        // Get a URL to the uploaded content
                        Log.d(TAG, "File upload successful");
                        Log.d(TAG, "From:" + fullPath);
                        Log.d(TAG, "To:" + fullFBPath);
//                        Toast tst = Toast.makeText(getApplicationContext(),"File upload Successful", Toast.LENGTH_SHORT);
//                        tst.show();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception exception) {
                        // Handle unsuccessful uploads
                        // ...
                        Log.d(TAG, "File upload unsuccessful");
                        Log.d(TAG, "From:" + fullPath);
                        Log.d(TAG, "To:" + fullFBPath);
//                        Toast tst = Toast.makeText(getApplicationContext(),"File upload Unsuccessful", Toast.LENGTH_SHORT);
//                        tst.show();
                    }
                });
        // Create file metadata including the content type
        StorageMetadata metadata = new StorageMetadata.Builder()
                .setContentType("audio/mp4")
                .setCustomMetadata(durationMetaDataConst, duration)
                .build();
        // Update metadata properties
        storageReference.updateMetadata(metadata)
                .addOnSuccessListener(new OnSuccessListener<StorageMetadata>() {
                    @Override
                    public void onSuccess(StorageMetadata storageMetadata) {
                        // Updated metadata is in storageMetadata
                        Log.d(TAG,"File metadata update successful");
                        Log.d(TAG,"For file: "+fireBaseFolder+"//"+filename);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception exception) {
                        // Uh-oh, an error occurred!
                        Log.d(TAG,"File metadata update unsuccessful");
                    }
                });
    }

    private String readRecentRecordingLength(){
        Uri uri = Uri.parse(recordPath + "/" + recordFile);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(this, uri);
        String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        int seconds = Integer.parseInt(durationStr) / 1000;
        int min = seconds / 60;
        seconds-=(min * 60);
        return (min < 10 ? "0" + min : String.valueOf(min)) + ":" + (seconds < 10 ? "0" + seconds : String.valueOf(seconds));
    }
}