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

package org.odk.collect.android.activities;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.GroupDef;
import org.javarosa.form.api.FormEntryCaption;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryPrompt;
import org.odk.collect.android.R;
import org.odk.collect.android.adapters.HierarchyListAdapter;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.exception.JavaRosaException;
import org.odk.collect.android.logic.FormController;
import org.odk.collect.android.logic.HierarchyElement;
import org.odk.collect.android.utilities.FormEntryPromptUtils;
import org.odk.collect.android.views.ODKView;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * Displays the structure of a form along with the answers for the current instance. Different form
 * elements are displayed in the following ways:
 * - Questions each take up a row with their full label shown and their answers below
 * - Non-repeat groups are not represented at all
 * - Repeat groups are initially shown as a "header" which takes you to a "picker" when tapped,
 *   revealing instances of that repeat
 * - Repeat instances are displayed with their label and index (e.g. `My group (1)`)
 *
 * Tapping on a repeat instance shows all the questions in that repeat instance using the display
 * rules above.
 *
 * Tapping on a question sets the app-wide current question to that question and terminates the
 * activity, returning to {@link FormEntryActivity}.
 *
 * Although the user gets the impression of navigating "into" a repeat, the view is refreshed in
 * {@link #refreshView()} rather than another activity/fragment being added to the back stack.
 *
 * Buttons at the bottom of the screen allow users to navigate the form.
 */
public class FormHierarchyActivity extends CollectAbstractActivity {
    /**
     * The questions and repeats at the current level.
     * Recreated every time {@link #refreshView()} is called.
     */
    private List<HierarchyElement> elementsToDisplay;

    /**
     * The label shown at the top of a hierarchy screen for a repeat instance. Set by
     * {@link #getCurrentPath()}.
     */
    private TextView groupPathTextView;

    /**
     * A ref to the current context group.
     * Useful to make sure we only render items inside of the group.
     */
    private String contextGroupRef;

    /**
     * If this index is non-null, we will render an intermediary "picker" view
     * showing the instances of the given repeat group.
     */
    private FormIndex repeatGroupPickerIndex;
    private static final String REPEAT_GROUP_PICKER_INDEX_KEY = "REPEAT_GROUP_PICKER_INDEX_KEY";

    /**
     * The index of the question or the field list the FormController was set to when the hierarchy
     * was accessed. Used to jump the user back to where they were if applicable.
     */
    private FormIndex startIndex;

    /**
     * The index of the question that is being displayed in the hierarchy. On first launch, it is
     * the same as {@link #startIndex}. It can then become the index of a repeat instance.
     */
    private FormIndex currentIndex;

    /**
     * The index of the screen that is being displayed in the hierarchy
     * (either the root of the form or a repeat group).
     */
    private FormIndex screenIndex;

    protected Button jumpPreviousButton;
    protected Button jumpBeginningButton;
    protected Button jumpEndButton;
    protected RecyclerView recyclerView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.hierarchy_layout);

        recyclerView = findViewById(R.id.list);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        TextView emptyView = findViewById(android.R.id.empty);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FormController formController = Collect.getInstance().getFormController();
        // https://github.com/opendatakit/collect/issues/998
        if (formController == null) {
            finish();
            Timber.w("FormController is null");
            return;
        }

        startIndex = formController.getFormIndex();

        setTitle(formController.getFormTitle());

        groupPathTextView = findViewById(R.id.pathtext);

        jumpPreviousButton = findViewById(R.id.jumpPreviousButton);
        jumpBeginningButton = findViewById(R.id.jumpBeginningButton);
        jumpEndButton = findViewById(R.id.jumpEndButton);

        configureButtons(formController);

        restoreInstanceState(savedInstanceState);

        refreshView();

        // Scroll to the last question the user was looking at
        // TODO: avoid another iteration through all displayed elements
        if (recyclerView != null && recyclerView.getAdapter() != null && recyclerView.getAdapter().getItemCount() > 0) {
            emptyView.setVisibility(View.GONE);
            recyclerView.post(() -> {
                int position = 0;
                // Iterate over all the elements currently displayed looking for a match with the
                // startIndex which can either represent a question or a field list.
                for (HierarchyElement hierarchyElement : elementsToDisplay) {
                    FormIndex indexToCheck = hierarchyElement.getFormIndex();
                    if (startIndex.equals(indexToCheck)
                            || (formController.indexIsInFieldList(startIndex) && indexToCheck.toString().startsWith(startIndex.toString()))) {
                        position = elementsToDisplay.indexOf(hierarchyElement);
                        break;
                    }
                }
                ((LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(position, 0);
            });
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putSerializable(REPEAT_GROUP_PICKER_INDEX_KEY, repeatGroupPickerIndex);
        super.onSaveInstanceState(outState);
    }

    private void restoreInstanceState(Bundle state) {
        if (state != null) {
            repeatGroupPickerIndex = (FormIndex) state.getSerializable(REPEAT_GROUP_PICKER_INDEX_KEY);
        }
    }

    /**
     * Configure the navigation buttons at the bottom of the screen.
     */
    void configureButtons(FormController formController) {
        jumpPreviousButton.setOnClickListener(v -> goUpLevel());

        jumpBeginningButton.setOnClickListener(v -> {
            formController.getTimerLogger().exitView();
            formController.jumpToIndex(FormIndex.createBeginningOfFormIndex());

            setResult(RESULT_OK);
            finish();
        });

        jumpEndButton.setOnClickListener(v -> {
            formController.getTimerLogger().exitView();
            formController.jumpToIndex(FormIndex.createEndOfFormIndex());

            setResult(RESULT_OK);
            finish();
        });
    }

    /**
     * Navigates "up" in the form hierarchy.
     */
    protected void goUpLevel() {
        FormController formController = Collect.getInstance().getFormController();

        // If `repeatGroupPickerIndex` is set it means we're currently displaying
        // a list of repeat instances. If we unset `repeatGroupPickerIndex`,
        // we will go back up to the previous screen.
        if (shouldShowRepeatGroupPicker()) {
            // Exit the picker.
            repeatGroupPickerIndex = null;
        } else {
            // Enter the picker if coming from a repeat group.
            if (formController.getEvent(screenIndex) == FormEntryController.EVENT_REPEAT) {
                repeatGroupPickerIndex = screenIndex;
            }

            Collect.getInstance().getFormController().stepToOuterScreenEvent();
        }

        refreshView();
    }

    /**
     * Builds a string representing the path of the current group. Each level is separated by `>`.
     */
    private String getCurrentPath() {
        FormController formController = Collect.getInstance().getFormController();
        FormIndex index = formController.getFormIndex();
        // move to enclosing group...
        index = formController.stepIndexOut(index);

        List<FormEntryCaption> groups = new ArrayList<>();
        while (index != null) {
            groups.add(0, formController.getCaptionPrompt(index));
            index = formController.stepIndexOut(index);
        }

        String path = ODKView.getGroupsPath(groups.toArray(new FormEntryCaption[groups.size()]));

        if (shouldShowRepeatGroupPicker()) {
            FormEntryCaption fc = formController.getCaptionPrompt(repeatGroupPickerIndex);
            String label = getLabel(fc);
            return TextUtils.isEmpty(path) ? label : path + " > " + label;
        } else {
            return path;
        }
    }

    /**
     * Goes to the start of the hierarchy view based on where the user came from.
     * Backs out until the index is at the beginning of a repeat group or the beginning of the form.
     */
    private void jumpToHierarchyStartIndex() {
        FormController formController = Collect.getInstance().getFormController();
        FormIndex startIndex = formController.getFormIndex();

        // If we're not at the first level, we're inside a repeated group so we want to only
        // display everything enclosed within that group.
        contextGroupRef = "";

        // Save the index to the screen itself, before potentially moving into it.
        screenIndex = startIndex;

        // If we're currently at a repeat node, record the name of the node and step to the next
        // node to display.
        if (formController.getEvent() == FormEntryController.EVENT_REPEAT) {
            contextGroupRef = getGroupRef(formController);
            formController.stepToNextEvent(FormController.STEP_INTO_GROUP);
        } else {
            FormIndex potentialStartIndex = formController.stepIndexOut(startIndex);
            // Step back until we hit a repeat or the beginning.
            while (!isScreenEvent(formController, potentialStartIndex)) {
                potentialStartIndex = formController.stepIndexOut(potentialStartIndex);
            }

            screenIndex = potentialStartIndex;

            if (potentialStartIndex == null) {
                // check to see if the question is at the first level of the hierarchy. If it
                // is, display the root level from the beginning.
                formController.jumpToIndex(FormIndex.createBeginningOfFormIndex());
            } else {
                // otherwise we're at a repeated group
                formController.jumpToIndex(potentialStartIndex);
            }

            int event = formController.getEvent();

            // now test again for repeat. This should be true at this point or we're at the
            // beginning
            if (event == FormEntryController.EVENT_REPEAT) {
                contextGroupRef = getGroupRef(formController);
                formController.stepToNextEvent(FormController.STEP_INTO_GROUP);
            }
        }
    }

    /**
     * Returns true if the event is an enclosing repeat or the start of the form.
     * See {@link FormController#stepToOuterScreenEvent} for more context.
     */
    private boolean isScreenEvent(FormController formController, FormIndex index) {
        // Beginning of form.
        if (index == null) {
            return true;
        }

        int event = formController.getEvent(index);
        return event == FormEntryController.EVENT_REPEAT;
    }

    private String getGroupRef(FormController formController) {
        return formController.getFormIndex().getReference().toString();
    }

    private String getParentGroupRef(FormController formController) {
        return formController.getFormIndex().getReference().getParentRef().toString();
    }

    private String getUnindexedGroupRef(FormController formController) {
        return getUnindexedGroupRef(formController.getFormIndex());
    }

    private String getUnindexedGroupRef(FormIndex index) {
        return index.getReference().toString(false);
    }

    private boolean shouldShowRepeatGroupPicker() {
        return repeatGroupPickerIndex != null;
    }

    /**
     * Rebuilds the view to reflect the elements that should be displayed based on the
     * FormController's current index. This index is either set prior to the activity opening or
     * mutated by {@link #onElementClick(HierarchyElement)} if a repeat instance was tapped.
     */
    public void refreshView() {
        try {
            FormController formController = Collect.getInstance().getFormController();

            // Save the current index so we can return to the problematic question
            // in the event of an error.
            currentIndex = formController.getFormIndex();

            elementsToDisplay = new ArrayList<>();

            jumpToHierarchyStartIndex();

            int event = formController.getEvent();

            if (event == FormEntryController.EVENT_BEGINNING_OF_FORM) {
                formController.stepToNextEvent(FormController.STEP_INTO_GROUP);
                contextGroupRef = getParentGroupRef(formController);
            }

            if (event == FormEntryController.EVENT_BEGINNING_OF_FORM && !shouldShowRepeatGroupPicker()) {
                // The beginning of form has no valid prompt to display.
                groupPathTextView.setVisibility(View.GONE);
                jumpPreviousButton.setEnabled(false);
            } else {
                groupPathTextView.setVisibility(View.VISIBLE);
                groupPathTextView.setText(getCurrentPath());
                jumpPreviousButton.setEnabled(true);
            }

            // Refresh the current event in case we did step forward.
            event = formController.getEvent();

            // Big change from prior implementation:
            //
            // The ref strings now include the instance number designations
            // i.e., [0], [1], etc. of the repeat groups (and also [1] for
            // non-repeat elements).
            //
            // The contextGroupRef is now also valid for the top-level form.
            //
            // The repeatGroupRef is null if we are not skipping a repeat
            // section.
            //
            String repeatGroupRef = null;

            event_search:
            while (event != FormEntryController.EVENT_END_OF_FORM) {

                // get the ref to this element
                String currentRef = getGroupRef(formController);
                String currentUnindexedRef = getUnindexedGroupRef(formController);

                // retrieve the current group
                String curGroup = (repeatGroupRef == null) ? contextGroupRef : repeatGroupRef;

                if (!currentRef.startsWith(curGroup)) {
                    // We have left the current group
                    if (repeatGroupRef == null) {
                        // We are done.
                        break;
                    } else {
                        // exit the inner repeat group
                        repeatGroupRef = null;
                    }
                }

                if (repeatGroupRef != null) {
                    // We're in a repeat group within the one we want to list
                    // skip this question/group/repeat and move to the next index.
                    event =
                            formController.stepToNextEvent(FormController.STEP_INTO_GROUP);
                    continue;
                }

                switch (event) {
                    case FormEntryController.EVENT_QUESTION:
                        if (shouldShowRepeatGroupPicker()) {
                            break;
                        }

                        FormEntryPrompt fp = formController.getQuestionPrompt();
                        String label = getLabel(fp);
                        if (!fp.isReadOnly() || (label != null && label.length() > 0)) {
                            // show the question if it is an editable field.
                            // or if it is read-only and the label is not blank.
                            String answerDisplay = FormEntryPromptUtils.getAnswerText(fp, this, formController);
                            elementsToDisplay.add(
                                    new HierarchyElement(FormEntryPromptUtils.markQuestionIfIsRequired(label, fp.isRequired()), answerDisplay, null,
                                            HierarchyElement.Type.QUESTION, fp.getIndex()));
                        }
                        break;
                    case FormEntryController.EVENT_GROUP:
                        // ignore group events
                        break;
                    case FormEntryController.EVENT_PROMPT_NEW_REPEAT:
                        // this would display the 'add new repeat' dialog
                        // ignore it.
                        break;
                    case FormEntryController.EVENT_REPEAT:
                        FormEntryCaption fc = formController.getCaptionPrompt();
                        // push this repeat onto the stack.
                        repeatGroupRef = currentRef;
                        // Because of the guard conditions above, we will skip
                        // everything until we exit this repeat.
                        //
                        // Note that currentRef includes the multiplicity of the
                        // repeat (e.g., [0], [1], ...), so every repeat will be
                        // detected as different and reach this case statement.
                        // Only the [0] emits the repeat header.
                        // Every one displays the descend-into action element.

                        if (shouldShowRepeatGroupPicker()) {
                            // Don't render other groups' instances.
                            String repeatGroupPickerRef = getUnindexedGroupRef(repeatGroupPickerIndex);
                            if (!currentUnindexedRef.startsWith(repeatGroupPickerRef)) {
                                break;
                            }

                            String repeatLabel = getLabel(fc);
                            if (fc.getFormElement().getChildren().size() == 1 && fc.getFormElement().getChild(0) instanceof GroupDef) {
                                formController.stepToNextEvent(FormController.STEP_INTO_GROUP);
                                FormEntryCaption fc2 = formController.getCaptionPrompt();
                                if (getLabel(fc2) != null) {
                                    repeatLabel = getLabel(fc2);
                                }
                            }
                            repeatLabel += " (" + (fc.getMultiplicity() + 1) + ")\u200E";

                            HierarchyElement instance = new HierarchyElement(
                                    repeatLabel, null,
                                    null, HierarchyElement.Type.REPEAT_INSTANCE, fc.getIndex());
                            elementsToDisplay.add(instance);
                        } else if (fc.getMultiplicity() == 0) {
                            // Display the repeat header for the group.
                            HierarchyElement group = new HierarchyElement(
                                    getLabel(fc), getString(R.string.repeatable_group_label),
                                    ContextCompat.getDrawable(this, R.drawable.ic_repeat),
                                    HierarchyElement.Type.REPEATABLE_GROUP, fc.getIndex());
                            elementsToDisplay.add(group);
                        }

                        break;
                }
                event =
                        formController.stepToNextEvent(FormController.STEP_INTO_GROUP);
            }

            recyclerView.setAdapter(new HierarchyListAdapter(elementsToDisplay, this::onElementClick));

            formController.jumpToIndex(currentIndex);
        } catch (Exception e) {
            Timber.e(e);
            createErrorDialog(e.getMessage());
        }
    }

    /**
     * Handles clicks on a specific row in the hierarchy view.
     */
    public void onElementClick(HierarchyElement element) {
        FormIndex index = element.getFormIndex();

        switch (element.getType()) {
            case QUESTION:
                onQuestionClicked(index);
                break;
            case REPEATABLE_GROUP:
                // Show the picker.
                repeatGroupPickerIndex = index;
                refreshView();
                break;
            case REPEAT_INSTANCE:
                // Hide the picker.
                repeatGroupPickerIndex = null;
                Collect.getInstance().getFormController().jumpToIndex(index);
                setResult(RESULT_OK);
                refreshView();
                break;
        }
    }

    /**
     * Handles clicks on a question. Jumps to the form filling view with the selected question shown.
     * If the selected question is in a field list, show the entire field list.
     */
    void onQuestionClicked(FormIndex index) {
        Collect.getInstance().getFormController().jumpToIndex(index);
        if (Collect.getInstance().getFormController().indexIsInFieldList()) {
            try {
                Collect.getInstance().getFormController().stepToPreviousScreenEvent();
            } catch (JavaRosaException e) {
                Timber.d(e);
                createErrorDialog(e.getCause().getMessage());
                return;
            }
        }
        setResult(RESULT_OK);
        finish();
    }

    /**
     * When the device back button is pressed, go back to the previous activity, NOT the previous
     * level in the hierarchy as the "Go Up" button does.
     */
    @Override
    public void onBackPressed() {
        FormController formController = Collect.getInstance().getFormController();
        if (formController != null) {
            formController.getTimerLogger().exitView();
            formController.jumpToIndex(startIndex);
        }

        onBackPressedWithoutLogger();
    }

    protected void onBackPressedWithoutLogger() {
        super.onBackPressed();
    }

    /**
     * Creates and displays dialog with the given errorMsg.
     */
    protected void createErrorDialog(String errorMsg) {
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();

        alertDialog.setIcon(android.R.drawable.ic_dialog_info);
        alertDialog.setTitle(getString(R.string.error_occured));
        alertDialog.setMessage(errorMsg);
        DialogInterface.OnClickListener errorListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                switch (i) {
                    case DialogInterface.BUTTON_POSITIVE:
                        FormController formController = Collect.getInstance().getFormController();
                        formController.jumpToIndex(currentIndex);
                        break;
                }
            }
        };
        alertDialog.setCancelable(false);
        alertDialog.setButton(getString(R.string.ok), errorListener);
        alertDialog.show();
    }

    private String getLabel(FormEntryCaption formEntryCaption) {
        return formEntryCaption.getShortText() != null && !formEntryCaption.getShortText().isEmpty()
                ? formEntryCaption.getShortText() : formEntryCaption.getLongText();
    }
}
