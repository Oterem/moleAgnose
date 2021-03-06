package omri.opencvdemo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings.Secure;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectMetadataRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;

import com.bumptech.glide.Glide;
import com.theartofdev.edmodo.cropper.CropImage;

import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
//import org.bytedeco.javacpp.opencv_core;
//import static org.bytedeco.javacpp.opencv_core.*;

public class MainActivity extends LoadingDialog {


    private ImageView mImageView;
    private Bitmap currentBitmap, calculatedBitmap;
    private ImageButton analyze_btn;
    private static final String TAG = "MainActivity";
    private static final String UPLOAD_BUCKET = "moleagnose-images";
    private static final String DOWNLOAD_BUCKET = "moleagnose-results";
    private String currentPhotoPath, currentGalleryPath;
    private static final int ACTION_IMAGE_CAPTURE = 1;
    private static final int ACTION_GET_CONTENT = 2;
    private static final int REQUEST_CAMERA = 100;
    private static int STATE = 3;
    private static final int SAMPLE_BLOB = 3;
    private static final int TIME_FOR_AWS_LAMBDA = 10;
    private static final int SAMPLE_SKIN = 4;
    private Uri photoURI;
    private ProgressBar pb, uploading_bar;
    private Point seed, skin;
    private double[] seedRGB, skinRGB, seedAvgColor, skinAvgColor;
    private double threshold;
    private static int SCALING_DIVIDER = 2;
    private String imageName = "";
    private double diff = 0;
    private boolean isImageSegmented = false;
    private Uri currentUri;
    private String uploadedKey = "";
    private Button download_btn;
    private String nameToDownload = "";


    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "static initializer: failed to load openCV");
        } else {
            Log.d(TAG, "static initializer: openCV loaded");
        }
    }


    /*----------------------------------------------------------------------------*/
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        pb = (ProgressBar) findViewById(R.id.progressbar_loading);
        uploading_bar = (ProgressBar) findViewById(R.id.loading_bar);
        pb.setVisibility(View.GONE);
        mImageView = (ImageView) findViewById(R.id.pic1);
        //download_btn = (Button)findViewById(R.id.download_btn);
        analyze_btn = (ImageButton) findViewById(R.id.analyze_btn);
        analyze_btn.setEnabled(false);
//        histogram_btn = (ImageButton) findViewById(R.id.hostogram_btn);
//        histogram_btn.setEnabled(false);
        AWSMobileClient.getInstance().initialize(this).execute();


        //handling permissions in case of SDK >=23
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }






    /*----------------------------------------------------------------------------*/

    /**
     * Returns a unique file for an image.
     *
     * @return the created file for saving an image.
     * @throws IOException in case of failure in file allocation
     */
    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );
        imageName = imageFileName;
        // Save a file: path for use with ACTION_VIEW intents
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    /*----------------------------------------------------------------------------*/

    /**
     * Creates an image capture intent, launching camera and after taking picture,
     * stores the image on internal storage.
     *
     * @param v current view.
     */
    public void launchCamera(View v) {

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            // Create the File where the photo should go
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                Toast.makeText(getApplicationContext(), R.string.create_file_error, Toast.LENGTH_LONG).show();
            }

            // Continue only if the File was successfully created
            if (photoFile != null) {
                photoURI = FileProvider.getUriForFile(this,
                        "com.omri.opencvdemo.fileprovider", photoFile);
                getBaseContext().grantUriPermission("com.omri.opencvdemo", photoURI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, ACTION_IMAGE_CAPTURE);
            }
        }
    }



    /*----------------------------------------------------------------------------*/
    public Uri getImageUri(Context inContext, Bitmap inImage) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        inImage.compress(Bitmap.CompressFormat.JPEG, 100, bytes);

        String path = MediaStore.Images.Media.insertImage(inContext.getContentResolver(), inImage, "Title", null);
        return Uri.parse(path);
    }

/*-------------------------------------------------------------------*/

    /**
     * For handling different intents
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {

            //histogram_btn.setEnabled(true);


            switch (requestCode) {
                case ACTION_IMAGE_CAPTURE: //in case user is taking a picture

                    try {

                        Intent intent = CropImage.activity(photoURI).getIntent(getBaseContext());
                        startActivityForResult(intent, CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE);
                    } catch (Exception e) {
                        Toast.makeText(getApplicationContext(), R.string.create_file_error, Toast.LENGTH_LONG).show();
                    }
                    break;

                case ACTION_GET_CONTENT: //in case user is loading picture from gallery
                    try {
                        photoURI = data.getData();
                        imageName = getPath(getApplicationContext(), photoURI);
                        imageName = imageName.replaceFirst(".*/(\\w+).*", "$1");
                        CropImage.activity(photoURI).start(this);
                        //setPic(currentBitmap, photoURI);
                        //startActivityForResult(intent, CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE);
                    } catch (Exception e) {
                        Toast.makeText(getApplicationContext(), R.string.load_image_error, Toast.LENGTH_LONG).show();
                    }
                    break;
                case CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE: //cropping image
                    CropImage.ActivityResult result = CropImage.getActivityResult(data);
                    Uri resultUri = result.getUri();
                    currentUri = resultUri;
                    photoURI = resultUri;
                    try {
                        Bitmap bm = MediaStore.Images.Media.getBitmap(this.getContentResolver(), resultUri);
                        currentBitmap = bm;
                        setPic(null, resultUri);
                    } catch (Exception e) {
                        Toast.makeText(getApplicationContext(), R.string.crop_image_error, Toast.LENGTH_LONG).show();
                    }
                    break;


            }
        }
    }

    /*----------------------------------------------------------------------------*/

    /**
     * This method asks the user to click on relevant location on screen.
     * After that, saves the coordinates of that location.
     */
    private void getBlobCoordinates() {

        final AlertDialog alertDialog = new AlertDialog.Builder(MainActivity.this)
                .setMessage(MainActivity.this.getString(R.string.sample_from_blob))
                .setPositiveButton("GOT IT", null).show();

        //setting a listener on imageview for sampling points
        mImageView.setOnTouchListener(new View.OnTouchListener() {

            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {


                //for sampling the point from not (not blob)
                if (STATE == SAMPLE_SKIN) {
                    skin = new Point();
                    DecodeActionDownEvent(view, motionEvent, skin);
                    try {
                        mImageView.buildDrawingCache();
                        Bitmap bitmap = mImageView.getDrawingCache();
                        int pixel = bitmap.getPixel((int) skin.x, (int) skin.y);
                        skinRGB = new double[]{Color.red(pixel), Color.green(pixel), Color.blue(pixel)};
                        // Log.i(TAG, "seed - r:" + seedRGB[2] + " ,g:" + seedRGB[1] + " b:" + seedRGB[0]);
                        // Log.i(TAG, "skin - r:" + skinRGB[2] + " ,g:" + skinRGB[1] + " b:" + skinRGB[0]);
                        mImageView.setOnTouchListener(null);
                        skinRGB = null;
                        seedRGB = null;

                        STATE = SAMPLE_BLOB;
                        // drawPointsOnImage();


                        seedAvgColor = PixelCalc.avgSurround(seed, bitmap);
                        skinAvgColor = PixelCalc.avgSurround(skin, bitmap);
                        // Log.i(TAG, "avgSeed - r:" + (int) seedAvgColor[0] + " ,g:" + (int) seedAvgColor[1] + " b:" + (int) seedAvgColor[2]);
                        // Log.i(TAG, "avgSkin - r:" + (int) skinAvgColor[0] + " ,g:" + (int) skinAvgColor[1] + " b:" + (int) skinAvgColor[2]);

                        threshold = PixelCalc.calcDistance(seedAvgColor, skinAvgColor) / SCALING_DIVIDER;
                        // Log.i(TAG, "Threshold is: " + threshold);
                        ImageButton b = (ImageButton) findViewById(R.id.analyze_btn);
                        b.setEnabled(false);

                        //uncomment this section to process image
                        SegmentAsyncTask work = new SegmentAsyncTask();
                        calculatedBitmap = currentBitmap;
                        Bitmap[] array = {calculatedBitmap, bitmap};
                        work.execute(array);


                        return false;

                    } catch (Exception e) {
                        e.printStackTrace();
                        //Toast.makeText(getBaseContext(), "Error while sampling colors", Toast.LENGTH_LONG).show();
                    }

                    return false;
                }

                //for sampling the point from suspicious blob
                if (STATE == SAMPLE_BLOB) {
                    seed = new Point();
                    DecodeActionDownEvent(view, motionEvent, seed);
                    try {
                        mImageView.buildDrawingCache();
                        Bitmap bitmap = mImageView.getDrawingCache();
                        int pixel = bitmap.getPixel((int) seed.x, (int) seed.y);
                        seedRGB = new double[]{Color.red(pixel), Color.green(pixel), Color.blue(pixel)};
                        STATE = SAMPLE_SKIN;
                        alertDialog.setMessage(MainActivity.this.getString(R.string.sample_from_skin));
                        alertDialog.show();

                        return false;
                    } catch (Exception e) {
                        e.printStackTrace();
                        // Toast.makeText(getBaseContext(), "Error while sampling colors", Toast.LENGTH_LONG).show();
                    }

                }
                return false;

            }
        });
    }

    /*-----------------------------------------------------------------------------------*/

    /**
     * Calculate coordinates relative to Imageview based on click location.
     * After calculation, this method set the X,Y coordinates correspond to the given Point object.
     *
     * @param v  Current view.
     * @param ev Relevant click event.
     * @param p  A Point object that it's coordinates will update after calculation.
     */
    private void DecodeActionDownEvent(View v, MotionEvent ev, Point p) {
        Matrix inverse = new Matrix();
        mImageView.getImageMatrix().invert(inverse);
        float[] touchPoint = new float[]{ev.getX(), ev.getY()};
        // inverse.mapPoints(touchPoint);
        p.x = touchPoint[0];
        p.y = touchPoint[1];


    }

    /*------------------------------------------------------------------------------------------------------*/

    /**
     * This method draws the two clicked points by user.
     * In red color it is the suspicious blob.
     * In blue color it is the "clean" skin.
     */
    private void drawPointsOnImage() {

        Bitmap bitmap;
        bitmap = currentBitmap.copy(currentBitmap.getConfig(), true);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(bitmap, 0, 0, null);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        canvas.drawCircle((float) seed.x, (float) seed.y, 20, paint);
        paint.setColor(Color.BLUE);
        canvas.drawCircle((float) skin.x, (float) skin.y, 20, paint);
        mImageView.setImageDrawable(new BitmapDrawable(getBaseContext().getResources(), bitmap));
    }

    /*----------------------------------------------------------------------------*/

    /**
     * Setting picture using Glide library in the ImageView based on given Bitmap.
     *
     * @param bm        Given Bitmap to present.
     * @param resultUri Given Uri of an image to present
     */
    private void setPic(Bitmap bm, Uri resultUri) {

        mImageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (bm != null) {
            mImageView.setImageBitmap(bm);
            return;
        }
        mImageView = (ImageView) findViewById(R.id.pic1);
        Glide
                .with(getBaseContext())
                .asBitmap().load(resultUri)
                .into(mImageView);
        analyze_btn.setEnabled(true);
    }

    String parseImageName(String path) {
        String[] tokens = path.split("/");
        String name = tokens[tokens.length - 1];
        String n = name.replace(".jpg", "");
        return name;
    }





    private File getTempFile(Context context) {
        File file = new File("");
        String name;
        try {
            String fileName = "omri";
            file = File.createTempFile(fileName, ".json", context.getCacheDir());
            String path = file.getAbsolutePath();
            name = parseImageName(path);

        } catch (IOException e) {
            // Error while creating file
        }
        return file;
    }

    public void downloadFromS3(){

        File f = getTempFile(getApplicationContext());
        nameToDownload = f.getName();



        TransferUtility transferUtility =
                TransferUtility.builder()
                        .context(getApplicationContext())
                        .awsConfiguration(AWSMobileClient.getInstance().getConfiguration())
                        .s3Client(new AmazonS3Client(AWSMobileClient.getInstance().getCredentialsProvider()))
                        .build();

        Log.i(TAG, "OT3: Downloading from s3 "+nameToDownload);
        Log.i(TAG, "key is: "+uploadedKey+".json");
        TransferObserver downloadObserver =
                transferUtility.download(DOWNLOAD_BUCKET,uploadedKey+".json",f);

        downloadObserver.setTransferListener(new TransferListener() {

            @Override
            public void onStateChanged(int id, TransferState state) {
                if (TransferState.COMPLETED == state) {
                    // Handle a completed upload.
                    Log.i(TAG, "OT3: Download complete");
                    String cachePath = getCacheDir().getPath();
        File myDisk = new File(cachePath);
        File json_string = new File(myDisk+File.separator+nameToDownload);
        String res = "";
        FileInputStream fis = null;
        JSONObject json = null;
        try {
            //f = new BufferedInputStream(new FileInputStream(filePath));
            //f.read(buffer);

            fis = new FileInputStream(json_string);
            char current;
            while (fis.available() > 0) {
                current = (char) fis.read();
                res = res + String.valueOf(current);
            }
        } catch (Exception e) {
            Log.d("TourGuide", e.toString());
        } finally {
            if (fis != null)
                try {
                    fis.close();
                } catch (IOException ignored) {
                Toast.makeText(getApplicationContext(),"Error parsing json", Toast.LENGTH_LONG);
                }
        }
        try{
            json = new JSONObject(res);
            JSONObject bigger = json.getJSONObject("bigger");
            String name = bigger.getString("name");
            double val = bigger.getDouble("value");
            Log.i(TAG, "========Final Score===========");
            Log.i(TAG, "OT: "+name+", "+val);
            final AlertDialog.Builder alertDialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Your diagnose")
                    .setMessage(name+": "+val)
                    .setPositiveButton("Got it",new DialogInterface.OnClickListener(){
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            //deleteCache(getApplicationContext());
                        }
                    });
            hideProgressDialog();
                    alertDialog.show();
            Toast.makeText(getApplicationContext(),name+": "+val+"%",Toast.LENGTH_LONG);
            Log.i(TAG, "========End of Final Score===========");

        }catch (Exception e){
            Toast.makeText(getApplicationContext(),"Error parsing json1", Toast.LENGTH_LONG);

        }



                }
            }

            @Override
            public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {

            }

            @Override
            public void onError(int id, Exception ex) {

            }
        });


    }





    public static void deleteCache(Context context) {
        try {
            File dir = context.getCacheDir();
            deleteDir(dir);
        } catch (Exception e) {}
    }

    public static boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            for (int i = 0; i < children.length; i++) {
                boolean success = deleteDir(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
            return dir.delete();
        } else if(dir!= null && dir.isFile()) {
            return dir.delete();
        } else {
            return false;
        }
    }

    /*----------------------------------------------------------------------------*/

    /**
     * Firing new  intent for browsing images from device.
     *
     * @param v Provided view.
     */
    public void onBrowseClick(View v) {

        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        TextView t = (TextView) findViewById(R.id.textView);
        t.setText("");
        startActivityForResult(Intent.createChooser(intent, "Select Picture"), ACTION_GET_CONTENT);
    }
    /*----------------------------------------------------------------------------*/

    public void onAnalyzeClick(View v) {

//        getBlobCoordinates();
        String path = getPath(getApplicationContext(), currentUri);
        UploadToS3AsyncTask job = new UploadToS3AsyncTask();

        if(isNetworkConnected()){
            job.execute(currentUri);
        }
        else{
            makeToast(Constants.Strings.NO_CONNECTION);
        }
    }

    private void makeToast(String msg){
        Toast.makeText(getApplicationContext(),msg,Toast.LENGTH_LONG).show();
    }
    public static String getPath(final Context context, final Uri uri) {
        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
        Log.i("URI", uri + "");
        String result = uri + "";
        // DocumentProvider
        //  if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
        if (isKitKat && (result.contains("media.documents"))) {
            String[] ary = result.split("/");
            int length = ary.length;
            String imgary = ary[length - 1];
            final String[] dat = imgary.split("%3A");
            final String docId = dat[1];
            final String type = dat[0];
            Uri contentUri = null;
            if ("image".equals(type)) {
                contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            } else if ("video".equals(type)) {
            } else if ("audio".equals(type)) {
            }
            final String selection = "_id=?";
            final String[] selectionArgs = new String[]{
                    dat[1]
            };
            return getDataColumn(context, contentUri, selection, selectionArgs);
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        return null;
    }

    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }


    /*----------------------------------------------------------------------------*/

    /**
     * The SegmentAsyncTask class implements the AsyncTask class.
     * It is used for a "heavy" image process
     */
    public class SegmentAsyncTask extends AsyncTask<Bitmap, Integer, Bitmap> {


        private Bitmap bm;
        private Bitmap flooded;

        /**
         * @return File that contain the name and the directory of the segment image file
         */

        private File getOutputSegmentFile() {//Give Name to the segment file.
            // To be safe, you should check that the SDCard is mounted
            // using Environment.getExternalStorageState() before doing this.
            File mediaStorageDir = new File(Environment.getExternalStorageDirectory()
                    + "/Android/data/"
                    + getApplicationContext().getPackageName()
                    + "/Files/SegmentFiles");


            // This location works best if you want the created images to be shared
            // between applications and persist after your app has been uninstalled.

            // Create the storage directory if it does not exist
            if (!mediaStorageDir.exists()) {
                if (!mediaStorageDir.mkdirs()) {
                    return null;
                }
            }
            // Create a media file name
            String timeStamp = new SimpleDateFormat("ddMMyyyy_HHmm").format(new Date());
            File mediaFile;
            String mImageName = "MI_" + timeStamp + ".jpg";
            mediaFile = new File(mediaStorageDir.getPath() + File.separator + imageName + "_seg.jpg");
            return mediaFile;
        }


        private void edgeTest(Bitmap bmp, Point p, int threshold, int replacementColor) {
            if (PixelCalc.calcDistance(seedAvgColor, new Point(p.x + 1, p.y), bmp) > threshold)//right neighbor
            {
                if (PixelCalc.calcDistance(seedAvgColor, new Point(p.x - 1, p.y), bmp) > threshold)//left neighbor
                {
                    if (PixelCalc.calcDistance(seedAvgColor, new Point(p.x, p.y - 1), bmp) > threshold)//up neighbor
                    {
                        if (PixelCalc.calcDistance(seedAvgColor, new Point(p.x, p.y + 1), bmp) > threshold)//down neighbor
                            bmp.setPixel((int) p.x, (int) p.y, Color.RED);
                    }
                }
            }
        }

        /*----------------------------------------------------------*/
        private void regionGrowing(Bitmap bmp, Point seed, int threshold, int replacementColor) {

            int x = (int) seed.x;
            int y = (int) seed.y;
            Queue<Point> q = new LinkedList<>();
            q.add(seed);
            while (q.size() > 0) {
                Point n = q.poll();//n is the head of list
                edgeTest(bmp, n, threshold, 1);
                if (PixelCalc.calcDistance(seedAvgColor, n, bmp) > threshold)//in case pixel does not belong
                    continue;

                Point e = new Point(n.x + 1, n.y);//right neighbor
                while ((n.x > 0) && (PixelCalc.calcDistance(seedAvgColor, n, bmp) <= threshold)) {
                    bmp.setPixel((int) n.x, (int) n.y, replacementColor);
                    if ((n.y > 0) && (PixelCalc.calcDistance(seedAvgColor, new Point(n.x, n.y - 1), bmp) <= threshold))//up
                        q.add(new Point(n.x, n.y - 1));
                    if ((n.y < bmp.getHeight() - 1) && (PixelCalc.calcDistance(seedAvgColor, new Point(n.x, n.y + 1), bmp) <= threshold))
                        q.add(new Point(n.x, n.y + 1));
                    n.x--;
                }
                while ((e.x < bmp.getWidth() - 1) && (PixelCalc.calcDistance(seedAvgColor, new Point(e.x, e.y), bmp) <= threshold)) {
                    bmp.setPixel((int) e.x, (int) e.y, replacementColor);

                    if ((e.y > 0) && (PixelCalc.calcDistance(seedAvgColor, new Point(e.x, e.y - 1), bmp) <= threshold))
                        q.add(new Point(e.x, e.y - 1));
                    if ((e.y < bmp.getHeight() - 1) && (PixelCalc.calcDistance(seedAvgColor, new Point(e.x, e.y + 1), bmp) <= threshold))
                        q.add(new Point(e.x, e.y + 1));
                    e.x++;
                }
            }
        }


        /*-----------------------------------------------------------*/


        /**
         * Set the view before image process.
         */
        @Override
        protected void onPreExecute() {
            int shortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);
            View v = findViewById(R.id.my_layout);
            v.setAlpha(.5f);
            pb.setVisibility(View.VISIBLE);
            pb.animate().setDuration(shortAnimTime).alpha(
                    1).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    pb.setVisibility(View.VISIBLE);

                }
            });
        }


        /*----------------------------------------------------------*/


        /**
         * After image process, this method stops the progress bar and present the processed image
         */
        @Override
        protected void onPostExecute(final Bitmap bitmap) {

            pb.setVisibility(View.INVISIBLE);

            pb.animate().setDuration(0).alpha(
                    0).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    pb.setVisibility(View.INVISIBLE);

                }
            });
            View v = findViewById(R.id.my_layout);
            v.setAlpha(1f);

            /*Validation mechanism*/
            final AlertDialog.Builder alertDialog = new AlertDialog.Builder(MainActivity.this)
                    .setMessage(MainActivity.this.getString(R.string.validate_segment))
                    .setPositiveButton("YES", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {


                            String path = getPath(getApplicationContext(), currentUri);
//                            WithTransferUtility(path);
                            UploadToS3AsyncTask job = new UploadToS3AsyncTask();
                            job.execute(currentUri);

                        }
                    })
                    .setNegativeButton("NO", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {

                            setPic(null, photoURI);
                            getBlobCoordinates();
                        }
                    });
            AlertDialog alert = alertDialog.create();
            ColorDrawable dialogColor = new ColorDrawable(0x88000000);
            // alert.getWindow().getAttributes().x = 100;
            alert.getWindow().setGravity(Gravity.CENTER);
            alert.getWindow().getAttributes().y = -200;
            alert.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);//disable dimmed background
            alert.show();
            // calculatedBitmap = Bitmap.createBitmap(bitmap);//aliasing
            mImageView.setImageResource(0);
            mImageView.destroyDrawingCache();
            TextView t = (TextView) findViewById(R.id.textView);
            // diff =Double.parseDouble(new DecimalFormat("##.##").format(diff));
            t.setText("difference is " + diff + "%");

            //---------- image saving-----------
            File pictureFile = null;
            BitmapFactory.Options myOptions = new BitmapFactory.Options();
            myOptions.inScaled = false;
            myOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;// important

            //mImageView.setImageBitmap(bitmap);
            calculatedBitmap = bitmap;
            isImageSegmented = true;
            setPic(bitmap, null);

            try {
                //pictureFile = createImageFile();
                pictureFile = getOutputSegmentFile();
            } catch (Exception e) {
                Toast.makeText(getBaseContext(), R.string.create_file_error, Toast.LENGTH_LONG).show();
            }

            if (pictureFile == null) {
                Log.d(TAG,
                        "Error creating media file, check storage permissions: ");// e.getMessage());
                return;
            }
            try {
                FileOutputStream fos = new FileOutputStream(pictureFile);

                calculatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.close();
            } catch (FileNotFoundException e) {
                Log.d(TAG, "File not found: " + e.getMessage());
            } catch (IOException e) {
                Log.d(TAG, "Error accessing file: " + e.getMessage());
            }
        }


        /*-----------------------------------------------------*/

        private double getMean(double[] array) {
            double sum = 0.0;
            for (double val : array) {
                sum += val;
            }
            return sum / array.length;
        }






        /**
         * This method perform image process
         */
        @Override
        protected Bitmap doInBackground(Bitmap... bitmaps) {

            bm = bitmaps[0];
            Mat src = new Mat();
            Mat dest = new Mat();
            Utils.bitmapToMat(bm, src);
            flooded = bitmaps[1];
            int red = android.graphics.Color.rgb(255, 255, 255);
            regionGrowing(flooded, seed, (int) threshold, red);
            Utils.bitmapToMat(flooded, src);
            //Imgproc.cvtColor(src, src, Imgproc.COLOR_BGR2GRAY);
            //Imgproc.threshold(src, src, 254, 254, Imgproc.THRESH_BINARY);



           /*create a mask with only the circle - to compare later with matchShapes*/
            /*Mat circle = new Mat(src.rows(), src.cols(), CvType.CV_8UC1);
            Imgproc.circle(circle, new Point(x, y), (int) maxRadius, new Scalar(255, 255, 255), 5);
            List<MatOfPoint> contours1 = new ArrayList<>();
            Mat hierarchy1 = new Mat();//for findContours calculation. Do not touch.
            Imgproc.threshold(circle, circle, 254, 254, Imgproc.THRESH_BINARY);
            Imgproc.findContours(circle, contours1, hierarchy1, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_NONE);
            */


            //this section is for masking the segment the mole in full color
            /*
            Mat original = new  Mat(1,1,CvType.CV_8UC3);//this is the original colored image
            Utils.bitmapToMat(bm,original);//loading original colored image to the matrix
            Imgproc.resize(original,original,new Size(src.width(),src.height()));//adapting and resizing the original to be same as src matrix dimentions
            Mat result = Mat.zeros(bm.getWidth(),bm.getHeight(),CvType.CV_8UC3);//creating result matrix full of zeros at the begining
            original.copyTo(result,src);//perform copy from original to result and using src matrix as mask
            */


            Bitmap bm = Bitmap.createBitmap(src.cols(), src.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(src, bm);


            return bm;
        }
      /*----------------------------------------------------------*/

    }



    public File getPublicAlbumStorageDir() {
        File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File picsDir = new File(dcimDir, "MyPics");
        picsDir.mkdirs(); //make if not exist
        File newFile = new File(picsDir, "omri.png");
        return newFile;

    }


//    public void downloadWithTransferUtility() {
//
//        //File f = getPublicAlbumStorageDir();
//
//
//        TransferUtility transferUtility =
//                TransferUtility.builder()
//                        .context(getApplicationContext())
//                        .awsConfiguration(AWSMobileClient.getInstance().getConfiguration())
//                        .s3Client(new AmazonS3Client(AWSMobileClient.getInstance().getCredentialsProvider()))
//                        .build();
//
//        try{
////            File file = new File(getApplicationContext().getFilesDir(), "omri.jpg");
//             File file = createImageFile();
//            //File file = getPublicAlbumStorageDir();
////            addImageToGallery(f.getAbsolutePath(), getApplicationContext());
//            TransferObserver downloadObserver =
//                    transferUtility.download(
//                            DOWNLOAD_BUCKET,uploadedKey+".json",
//                            file);
//            downloadObserver.setTransferListener(new TransferListener() {
//
//                @Override
//                public void onStateChanged(int id, TransferState state) {
//                    if (TransferState.COMPLETED == state) {
//                        // Handle a completed upload.
//                    }
//                }
//
//                @Override
//                public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
//                    float percentDonef = ((float)bytesCurrent/(float)bytesTotal) * 100;
//                    int percentDone = (int)percentDonef;
//
//                    Log.d("MainActivity", "   ID:" + id + "   bytesCurrent: " + bytesCurrent + "   bytesTotal: " + bytesTotal + " " + percentDone + "%");
//                }
//
//                @Override
//                public void onError(int id, Exception ex) {
//                    // Handle errors
//                }
//
//            });
//
//            // If you prefer to poll for the data, instead of attaching a
//            // listener, check for the state and progress in the observer.
//            if (TransferState.COMPLETED == downloadObserver.getState()) {
//                // Handle a completed upload.
//            }
//
//            Log.d("YourActivity", "Bytes Transferrred: " + downloadObserver.getBytesTransferred());
//            Log.d("YourActivity", "Bytes Total: " + downloadObserver.getBytesTotal());
//
//        }catch (Exception e){
//            Toast.makeText(getApplicationContext(),"error in download", Toast.LENGTH_LONG);
//        }
//
//
//         //File file = new File(Environment.getExternalStorageDirectory().toString() + "/" + key);
//
//
//
//       // String path = getPath(getApplicationContext(),currentUri);
//
//
//        // Attach a listener to the observer to get state update and progress notifications
//
//    }


    private boolean isNetworkConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null;
    }

    private class DownloadFromS3AsyncTask extends AsyncTask<Void,Void,Void>{

        @Override
        protected Void doInBackground(Void... voids) {
            downloadFromS3();
            return null;
        }

        @Override
        protected void onPreExecute()
        {

            showProgressDialog(Constants.Strings.DOWNLOAD_IMAGE);}

        @Override
        protected void onPostExecute(Void aVoid) {}
    }




    private class UploadToS3AsyncTask extends AsyncTask<Uri, Integer, Void> {



        public void uploadWithTransferUtility(String path) {
            String android_id = Secure.getString(getBaseContext().getContentResolver(),
                    Secure.ANDROID_ID);
            TransferUtility transferUtility =
                    TransferUtility.builder()
                            .context(getApplicationContext())
                            .awsConfiguration(AWSMobileClient.getInstance().getConfiguration())
                            .s3Client(new AmazonS3Client(AWSMobileClient.getInstance().getCredentialsProvider()))
                            .build();

            uploadedKey = (android_id+"_"+imageName).replace(".", "_");
            TransferObserver uploadObserver =
                    transferUtility.upload(UPLOAD_BUCKET,uploadedKey+".jpg",new File(path));


            // Attach a listener to the observer to get state update and progress notifications
            uploadObserver.setTransferListener(new TransferListener() {


                @Override
                public void onStateChanged(int id, TransferState state) {
                    if (TransferState.COMPLETED == state) {
                        // Handle a completed upload.
                        Log.d("YourActivity", "Upload Complete");


                    }
                }

                @Override
                public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
                    float percentDonef = ((float) bytesCurrent / (float) bytesTotal) * 100;
                    int percentDone = (int) percentDonef;
                    onProgressUpdate(percentDone);



                    Log.d("YourActivity", "ID:" + id + " bytesCurrent: " + bytesCurrent
                            + " bytesTotal: " + bytesTotal + " " + percentDone + "%");
                }

                @Override
                public void onError(int id, Exception ex) {
                    Log.d(TAG,"Error in upload");
                }

            });

            // If you prefer to poll for the data, instead of attaching a
            // listener, check for the state and progress in the observer.
            if (TransferState.COMPLETED == uploadObserver.getState()) {
                Log.d("YourActivity", "Upload Complete");

            }
//        Toast.makeText(getApplicationContext(), "Upload to server completed", Toast.LENGTH_LONG);
            Log.d("YourActivity", "Bytes Transferrred: " + uploadObserver.getBytesTransferred());
            Log.d("YourActivity", "Bytes Total: " + uploadObserver.getBytesTotal());
        }
        /**
         * Set the view before image process.
         */
        @Override
        protected void onPreExecute() {
            showProgressDialog(Constants.Strings.UPLOAD_IMAGE);
        }

        @Override
        protected void onPostExecute(Void Void) {
            hideProgressDialog();
            DownloadFromS3AsyncTask myWork = new DownloadFromS3AsyncTask();
            myWork.execute();
        }

        @Override
        protected Void doInBackground(Uri... Uri) {

            String path = getPath(getApplicationContext(), Uri[0]);
            uploadWithTransferUtility(path);
            for(int i=0;i<TIME_FOR_AWS_LAMBDA;i++){
                try {
                    Thread.sleep(1000);

                }catch (Exception e){
                    e.printStackTrace();;
                }
            }
            return null;
        }


    }

}



