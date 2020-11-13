/*
 * Copyright (C) 2009 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.widgets;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.MediaStore.Video;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.StringData;
import org.odk.collect.android.BuildConfig;
import org.odk.collect.android.R;
import org.odk.collect.android.activities.CaptureSelfieVideoActivity;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.formentry.questions.QuestionDetails;
import org.odk.collect.android.formentry.questions.WidgetViewUtils;
import org.odk.collect.android.listeners.PermissionListener;
import org.odk.collect.android.preferences.GeneralKeys;
import org.odk.collect.android.utilities.CameraUtils;
import org.odk.collect.android.utilities.ContentUriProvider;
import org.odk.collect.android.utilities.FileUtil;
import org.odk.collect.android.utilities.FileUtils;
import org.odk.collect.android.utilities.MediaUtils;
import org.odk.collect.android.utilities.QuestionMediaManager;
import org.odk.collect.android.utilities.ToastUtils;
import org.odk.collect.android.utilities.WidgetAppearanceUtils;
import org.odk.collect.android.widgets.interfaces.WidgetDataReceiver;
import org.odk.collect.android.widgets.interfaces.ButtonClickListener;
import org.odk.collect.android.widgets.interfaces.FileWidget;
import org.odk.collect.android.widgets.utilities.WaitingForDataRegistry;

import java.io.File;
import java.util.Locale;

import timber.log.Timber;

import static org.odk.collect.android.analytics.AnalyticsEvents.REQUEST_HIGH_RES_VIDEO;
import static org.odk.collect.android.analytics.AnalyticsEvents.REQUEST_VIDEO_NOT_HIGH_RES;
import static org.odk.collect.android.formentry.questions.WidgetViewUtils.createSimpleButton;
import static org.odk.collect.android.utilities.ApplicationConstants.RequestCodes;

/**
 * Widget that allows user to take pictures, sounds or video and add them to the
 * form.
 *
 * @author Carl Hartung (carlhartung@gmail.com)
 * @author Yaw Anokwa (yanokwa@gmail.com)
 */
@SuppressLint("ViewConstructor")
public class VideoWidget extends QuestionWidget implements FileWidget, ButtonClickListener, WidgetDataReceiver {

    public static final boolean DEFAULT_HIGH_RESOLUTION = true;

    @NonNull
    private MediaUtils mediaUtils;

    private final WaitingForDataRegistry waitingForDataRegistry;
    private final QuestionMediaManager questionMediaManager;

    @NonNull
    private FileUtil fileUtil;

    Button captureButton;
    Button playButton;
    Button chooseButton;
    private String binaryName;

    private boolean selfie;

    public VideoWidget(Context context, QuestionDetails prompt,  QuestionMediaManager questionMediaManager, WaitingForDataRegistry waitingForDataRegistry) {
        this(context, prompt, new FileUtil(), new MediaUtils(), waitingForDataRegistry, questionMediaManager, new CameraUtils());
    }

    public VideoWidget(Context context, QuestionDetails questionDetails, @NonNull FileUtil fileUtil, @NonNull MediaUtils mediaUtils,
                       WaitingForDataRegistry waitingForDataRegistry, QuestionMediaManager questionMediaManager, CameraUtils cameraUtils) {
        super(context, questionDetails);

        this.fileUtil = fileUtil;
        this.mediaUtils = mediaUtils;
        this.waitingForDataRegistry = waitingForDataRegistry;
        this.questionMediaManager = questionMediaManager;

        selfie = WidgetAppearanceUtils.isFrontCameraAppearance(getFormEntryPrompt());

        captureButton = createSimpleButton(getContext(), R.id.capture_video, questionDetails.isReadOnly(), getContext().getString(R.string.capture_video), getAnswerFontSize(), this);

        chooseButton = createSimpleButton(getContext(), R.id.choose_video, questionDetails.isReadOnly(), getContext().getString(R.string.choose_video), getAnswerFontSize(), this);

        playButton = createSimpleButton(getContext(), R.id.play_video, false, getContext().getString(R.string.play_video), getAnswerFontSize(), this);
        playButton.setVisibility(VISIBLE);

        // retrieve answer from data model and update ui
        binaryName = questionDetails.getPrompt().getAnswerText();
        playButton.setEnabled(binaryName != null);

        // finish complex layout
        LinearLayout answerLayout = new LinearLayout(getContext());
        answerLayout.setOrientation(LinearLayout.VERTICAL);
        answerLayout.addView(captureButton);
        answerLayout.addView(chooseButton);
        answerLayout.addView(playButton);
        addAnswerView(answerLayout, WidgetViewUtils.getStandardMargin(context));

        hideButtonsIfNeeded();

        if (selfie) {
            if (!cameraUtils.isFrontCameraAvailable()) {
                captureButton.setEnabled(false);
                ToastUtils.showLongToast(R.string.error_front_camera_unavailable);
            }
        }
    }

    @Override
    public void deleteFile() {
        questionMediaManager.deleteAnswerFile(getFormEntryPrompt().getIndex().toString(),
                        getInstanceFolder() + File.separator + binaryName);
        binaryName = null;
    }

    @Override
    public void clearAnswer() {
        // remove the file
        deleteFile();

        // reset buttons
        playButton.setEnabled(false);

        widgetValueChanged();
    }

    @Override
    public IAnswerData getAnswer() {
        if (binaryName != null) {
            return new StringData(binaryName);
        } else {
            return null;
        }
    }

    /**
     * Set this widget with the actual file returned by OnActivityResult.
     * Both of Uri and File are supported.
     * If the file is local, a Uri is enough for the copy task below.
     * If the chose file is from cloud(such as Google Drive),
     * The retrieve and copy task is already executed in the previous step,
     * so a File object would be presented.
     *
     * @param object Uri or File of the chosen file.
     * @see org.odk.collect.android.activities.FormEntryActivity#onActivityResult(int, int, Intent)
     */
    @Override
    public void setData(Object object) {
        File newVideo = null;
        // get the file path and create a copy in the instance folder
        if (object instanceof Uri) {
            String sourcePath = getSourcePathFromUri((Uri) object);
            String destinationPath = mediaUtils.getDestinationPathFromSourcePath(sourcePath, getInstanceFolder(), fileUtil);
            File source = fileUtil.getFileAtPath(sourcePath);
            newVideo = fileUtil.getFileAtPath(destinationPath);
            fileUtil.copyFile(source, newVideo);
        } else if (object instanceof File) {
            newVideo = (File) object;
        } else {
            Timber.w("VideoWidget's setBinaryData must receive a File or Uri object.");
            return;
        }

        if (newVideo.exists()) {
           questionMediaManager.replaceAnswerFile(getFormEntryPrompt().getIndex().toString(), newVideo.getAbsolutePath());
        } else {
            Timber.e("Inserting Video file FAILED");
        }
        // you are replacing an answer. remove the media.
        if (binaryName != null && !binaryName.equals(newVideo.getName())) {
            deleteFile();
        }

        binaryName = newVideo.getName();
        widgetValueChanged();
        playButton.setEnabled(binaryName != null);
    }

    private void hideButtonsIfNeeded() {
        if (selfie || (getFormEntryPrompt().getAppearanceHint() != null
                && getFormEntryPrompt().getAppearanceHint().toLowerCase(Locale.ENGLISH).contains(WidgetAppearanceUtils.NEW))) {
            chooseButton.setVisibility(View.GONE);
        }
    }

    private String getSourcePathFromUri(@NonNull Uri uri) {
        return mediaUtils.getPathFromUri(getContext(), uri, Video.Media.DATA);
    }

    @Override
    public void setOnLongClickListener(OnLongClickListener l) {
        captureButton.setOnLongClickListener(l);
        chooseButton.setOnLongClickListener(l);
        playButton.setOnLongClickListener(l);
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        captureButton.cancelLongPress();
        chooseButton.cancelLongPress();
        playButton.cancelLongPress();
    }

    @Override
    public void onButtonClick(int id) {
        switch (id) {
            case R.id.capture_video:
                if (selfie) {
                    getPermissionUtils().requestCameraAndRecordAudioPermissions((Activity) getContext(), new PermissionListener() {
                        @Override
                        public void granted() {
                            captureVideo();
                        }

                        @Override
                        public void denied() {
                        }
                    });
                } else {
                    getPermissionUtils().requestCameraPermission((Activity) getContext(), new PermissionListener() {
                        @Override
                        public void granted() {
                            captureVideo();
                        }

                        @Override
                        public void denied() {
                        }
                    });
                }
                break;
            case R.id.choose_video:
                chooseVideo();
                break;
            case R.id.play_video:
                playVideoFile();
                break;
        }
    }

    private void captureVideo() {
        Intent i;
        if (selfie) {
            i = new Intent(getContext(), CaptureSelfieVideoActivity.class);
        } else {
            i = new Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT,
                    Video.Media.EXTERNAL_CONTENT_URI.toString());
        }

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(Collect
                .getInstance());

        // request high resolution if configured for that...
        boolean highResolution = settings.getBoolean(
                GeneralKeys.KEY_HIGH_RESOLUTION,
                VideoWidget.DEFAULT_HIGH_RESOLUTION);
        if (highResolution) {
            i.putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 1);
            analytics.logEvent(REQUEST_HIGH_RES_VIDEO, getQuestionDetails().getFormAnalyticsID(), "");
        } else {
            analytics.logEvent(REQUEST_VIDEO_NOT_HIGH_RES, getQuestionDetails().getFormAnalyticsID(), "");
        }
        try {
            waitingForDataRegistry.waitForData(getFormEntryPrompt().getIndex());
            ((Activity) getContext()).startActivityForResult(i,
                    RequestCodes.VIDEO_CAPTURE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                    getContext(),
                    getContext().getString(R.string.activity_not_found,
                            getContext().getString(R.string.capture_video)), Toast.LENGTH_SHORT)
                    .show();
            waitingForDataRegistry.cancelWaitingForData();
        }
    }

    private void chooseVideo() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("video/*");
        // Intent i =
        // new Intent(Intent.ACTION_PICK,
        // android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        try {
            waitingForDataRegistry.waitForData(getFormEntryPrompt().getIndex());
            ((Activity) getContext()).startActivityForResult(i,
                    RequestCodes.VIDEO_CHOOSER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                    getContext(),
                    getContext().getString(R.string.activity_not_found,
                            getContext().getString(R.string.choose_video)), Toast.LENGTH_SHORT)
                    .show();

            waitingForDataRegistry.cancelWaitingForData();
        }
    }

    private void playVideoFile() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        File file = new File(getInstanceFolder() + File.separator + binaryName);

        Uri uri = null;
        try {
            uri = ContentUriProvider.getUriForFile(getContext(), BuildConfig.APPLICATION_ID + ".provider", file);
            FileUtils.grantFileReadPermissions(intent, uri, getContext());
        } catch (IllegalArgumentException e) {
            Timber.e(e);
        }

        intent.setDataAndType(uri, "video/*");
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                    getContext(),
                    getContext().getString(R.string.activity_not_found,
                            getContext().getString(R.string.view_video)), Toast.LENGTH_SHORT).show();
        }
    }
}
