package beardapps.com.aketa_voice_android;

import android.app.ProgressDialog;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.ActionBarActivity;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public class MainActivity extends ActionBarActivity {
    private static final String APP_TAG = "Aketa_audio_recorder";

    //Audio Settings
    private static final int RECORDER_BPP = 16;
    private static final String AUDIO_RECORDER_FILE_EXT_WAV = ".wav";
    private static final String AUDIO_RECORDER_FOLDER = "AudioRecorder";
    private static final String AUDIO_RECORDER_TEMP_FILE = "recorder_temp.raw";
    private static final String AUDIO_RECORDER_LAST_FILE = "recorder_file";
    private static final int RECORDER_SAMPLERATE = 8000;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord recorder = null;
    private int bufferSize = 0;
    private Thread recordingThread = null;
    private android.os.Handler recordingRepeat = null;
    private boolean isRecording = false;

    private int secondsCounter = 0;

    private ImageView imageButton;
    private EditText pinCodeText;
    private Button loginBtn;
    private TextView statusLabel;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageButton = (ImageView)findViewById(R.id.imageButton);
        pinCodeText = (EditText) findViewById(R.id.editText);
        loginBtn = (Button) findViewById(R.id.button);
        statusLabel = (TextView) findViewById(R.id.statusLabel);


        imageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recordingAudio();
            }
        });

        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(pinCodeText.getText().toString().equalsIgnoreCase("")) {
                    return;
                }

                sentAudioToServer2();
            }
        });

    }

    private String getFileName() {
        String filepath = Environment.getExternalStorageDirectory().getPath();
        File file = new File(filepath, AUDIO_RECORDER_FOLDER);

        if(!file.exists()) {
            file.mkdirs();
        }

        return (file.getAbsolutePath() + "/" + AUDIO_RECORDER_LAST_FILE + AUDIO_RECORDER_FILE_EXT_WAV);
    }

    private String getTempFilename() {
        String filepath = Environment.getExternalStorageDirectory().getPath();
        File file = new File(filepath, AUDIO_RECORDER_FOLDER);

        if(!file.exists()) {
            file.mkdirs();
        }

        File tempFile = new File(filepath, AUDIO_RECORDER_TEMP_FILE);

        if(tempFile.exists()) {
            tempFile.delete();
        }

        return (file.getAbsolutePath() + "/" + AUDIO_RECORDER_TEMP_FILE);
    }

    public void recordingAudio() {
        statusLabel.setBackgroundColor(Color.TRANSPARENT);
        pinCodeText.setText("");



        if(!isRecording) {
            deleteTempFile();

            bufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE,
                    RECORDER_CHANNELS,
                    RECORDER_AUDIO_ENCODING);

            //bufferSize = bufferSize/2;

            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING, bufferSize);

            int i = recorder.getState();
            if(i==1) {
                recorder.startRecording();
            }

            isRecording = true;
            imageButton.setImageResource(R.drawable.mic_recording);

            recordingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    writeAudioDataToFile();
                }
            }, "AudioRecorder Thread");

            recordingThread.start();

            recordingRepeat = new android.os.Handler();
            recordingRepeat.postDelayed(updaterTimerThread, 0);

            deleteCurrentFile();

            visiblePinView(false);
        } else {
            isRecording = false;
            secondsCounter = 0;

            if(recorder != null) {
                int i = recorder.getState();
                if(i==1) {
                    recorder.stop();
                }

                imageButton.setImageResource(R.drawable.mic_stop);

                recorder.release();

                recorder = null;
                recordingThread = null;
                recordingRepeat = null;
            }

            statusLabel.setText("Recording stoped");

            //copyWaveFile(getTempFilename(), getFileName());
            //deleteTempFile();

            visiblePinView(true);
        }
    }

    private Runnable updaterTimerThread = new Runnable() {
        @Override
        public void run() {
            if(recordingRepeat != null) {
                secondsCounter += 1;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusLabel.setText("Recording "+secondsCounter+" sec ...");
                    }
                });

                recordingRepeat.postDelayed(this, 1000);
            }
        }
    };

    private void deleteTempFile() {
        File file = new File(getTempFilename());

        file.delete();
    }

    private void deleteCurrentFile() {
        File file = new File(getFileName());
        if(file.exists()) {
            file.delete();
        }
    }

    private void copyWaveFile(String inFilename,String outFilename){
        FileInputStream in = null;
        FileOutputStream out = null;
        long totalAudioLen = 0;
        long totalDataLen = totalAudioLen + 36;
        long longSampleRate = RECORDER_SAMPLERATE;
        int channels = 1;
        long byteRate = RECORDER_BPP * RECORDER_SAMPLERATE * channels/8;

        byte[] data = new byte[bufferSize];

        try {
            in = new FileInputStream(inFilename);
            out = new FileOutputStream(outFilename);
            totalAudioLen = in.getChannel().size();
            totalDataLen = totalAudioLen + 36;

            MainActivity.logString("File size: " + totalDataLen);

            WriteWaveFileHeader(out, totalAudioLen, totalDataLen,
                    longSampleRate, channels, byteRate);

            while(in.read(data) != -1){
                out.write(data);
            }

            in.close();
            out.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void WriteWaveFileHeader(
            FileOutputStream out, long totalAudioLen,
            long totalDataLen, long longSampleRate, int channels,
            long byteRate) throws IOException {

        byte[] header = new byte[44];

        header[0] = 'R';  // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';  // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;  // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;  // format = 1
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (2 * 16 / 8);  // block align
        header[33] = 0;
        header[34] = RECORDER_BPP;  // bits per sample
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }


    private void writeAudioDataToFile() {
        byte data[] = new byte[bufferSize];
        String filename = getTempFilename();
        FileOutputStream os = null;

        try {
            os = new FileOutputStream(filename);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        int read = 0;

        if(null != os) {
            while (isRecording) {
                read = recorder.read(data, 0, bufferSize);

                byte data8bit[] = new byte[bufferSize/2];

                if(AudioRecord.ERROR_INVALID_OPERATION != read) {
                    try {

                        short[] shorts = new short[read/2];
                        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
                        for (int i = 0 ; i < shorts.length ; ++i) {
                            data8bit[i] = (byte)(shorts[i]>>8);
                        }


                        os.write(data8bit);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            try{
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private String getBase64Data() {

        FileInputStream fileInputStream = null;

        File file = new File(getTempFilename());
        if(!file.exists()) {
            return "";
        }

        byte[] bytes = new byte[(int) file.length()];
        try {
            fileInputStream = new FileInputStream(file);
            fileInputStream.read(bytes);
            fileInputStream.close();

        } catch (Exception e) {

        }

        String encodedBase64 = Base64.encodeToString(bytes, 0);
        logString(encodedBase64);

        return encodedBase64;

    }

    private ProgressDialog progress;
    public void showProgressDialog() {
        progress = new ProgressDialog(this);
        progress.setTitle("Loading");
        progress.setMessage("Wait while loading...");
        progress.show();
    }

    public void hideProgressDialog() {
        if(progress != null) {
            progress.dismiss();
        }
    }

    public void setResultData(String text) {
        if(text.equalsIgnoreCase("User not found")) {
            statusLabel.setBackgroundColor(Color.RED);
        } else {
            statusLabel.setBackgroundColor(Color.GREEN);
        }
        statusLabel.setText(text);
    }

    private void sentAudioToServer2() {
        Thread t = new Thread() {

            public void run() {
                //Looper.prepare();
                HttpClient httpclient = new DefaultHttpClient();

                JSONObject obj = new JSONObject();
                try {
                    obj.put("login", getBase64Data()+"");
                    obj.put("password", pinCodeText.getText().toString()+"");
                } catch (Exception e) {

                }

                try {

                    HttpPost httpPost = new HttpPost(new URL("https://www.lybs.fr/AketaDemo/rest/session/login").toURI());

                    httpPost.setEntity(new StringEntity(obj.toString(), "UTF-8"));

                    String jsonString = obj.toString();

                    httpPost.setHeader("Content-Type", "application/json");
                    httpPost.setHeader("Accept-Encoding", "application/json");
                    httpPost.setHeader("Accept-Language", "en-US");

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showProgressDialog();
                        }
                    });

                    HttpResponse response = httpclient.execute(httpPost);
                    final String temp = EntityUtils.toString(response.getEntity());
                    Log.i("tag", temp);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideProgressDialog();
                        }
                    });

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setResultData(temp);
                            visiblePinView(false);
                        }
                    });


                } catch (ClientProtocolException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                }

                //Looper.loop(); //Loop in the message queue
            }
        };

        t.start();
    }

    private void visiblePinView(boolean isVisible) {
        if(isVisible) {
            pinCodeText.setVisibility(View.VISIBLE);
            loginBtn.setVisibility(View.VISIBLE);
        } else {
            pinCodeText.setVisibility(View.GONE);
            loginBtn.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static int logString(String message) {
        return Log.d(APP_TAG, message);
    }
}
