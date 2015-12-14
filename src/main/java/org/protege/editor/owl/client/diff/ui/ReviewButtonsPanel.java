package org.protege.editor.owl.client.diff.ui;

import org.protege.editor.core.Disposable;
import org.protege.editor.core.ProtegeApplication;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.client.diff.model.*;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.owl.server.api.ChangeMetaData;
import org.protege.owl.server.api.client.Client;
import org.protege.owl.server.api.client.VersionedOntologyDocument;
import org.protege.owl.server.api.exception.OWLServerException;
import org.protege.owl.server.util.ClientUtilities;
import org.semanticweb.owlapi.model.OWLOntologyChange;

import javax.swing.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Rafael Gonçalves <br>
 * Stanford Center for Biomedical Informatics Research
 */
public class ReviewButtonsPanel extends JPanel implements Disposable {
    private static final long serialVersionUID = 2970244966462590949L;
    private LogDiffManager diffManager;
    private ReviewManager reviewManager;
    private OWLEditorKit editorKit;
    private JButton rejectBtn, clearBtn, acceptBtn, commitBtn;

    /**
     * Constructor
     *
     * @param modelManager  OWL model manager
     * @param editorKit OWL editor kit
     */
    public ReviewButtonsPanel(OWLModelManager modelManager, OWLEditorKit editorKit) {
        this.editorKit = checkNotNull(editorKit);
        this.diffManager = LogDiffManager.get(modelManager, editorKit);
        this.reviewManager = diffManager.getReviewManager();
        setLayout(new FlowLayout(FlowLayout.CENTER, 2, 3));
        addButtons();
    }

    private void addButtons() {
        rejectBtn = getButton("Reject", rejectBtnListener);
        rejectBtn.setToolTipText("Reject selected change(s); rejected changes are undone");

        clearBtn = getButton("Clear", clearBtnListener);
        clearBtn.setToolTipText("Reset selected change(s) to review-pending status");

        acceptBtn = getButton("Accept", acceptBtnListener);
        acceptBtn.setToolTipText("Accept selected change(s)");

        commitBtn = getButton("Commit", commitBtnListener);
        commitBtn.setToolTipText("Commit all change reviews");

        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setPreferredSize(new Dimension(20, 0));

        add(rejectBtn); add(clearBtn); add(acceptBtn); add(separator); add(commitBtn);
        diffManager.addListener(changeSelectionListener);
    }

    private LogDiffListener changeSelectionListener = new LogDiffListener() {
        @Override
        public void statusChanged(LogDiffEvent event) {
            if(event.equals(LogDiffEvent.CHANGE_SELECTION_CHANGED)) {
                if(!diffManager.getSelectedChanges().isEmpty()) {
                    enable(true, acceptBtn, clearBtn, rejectBtn);
                }
                else {
                    enable(false, acceptBtn, clearBtn, rejectBtn);
                }
            }
            if(event.equals(LogDiffEvent.CHANGE_REVIEWED) || event.equals(LogDiffEvent.ONTOLOGY_UPDATED)) {
                if(reviewManager.hasUncommittedReviews()) {
                    enable(true, commitBtn);
                }
                else {
                    enable(false, commitBtn);
                }
            }
        }
    };

    private ActionListener rejectBtnListener = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
            for(Change c : diffManager.getSelectedChanges()) {
                reviewManager.setReviewStatus(c, ReviewStatus.REJECTED);
            }
            diffManager.statusChanged(LogDiffEvent.CHANGE_REVIEWED);
        }
    };

    private ActionListener acceptBtnListener = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
            for(Change c : diffManager.getSelectedChanges()) {
                reviewManager.setReviewStatus(c, ReviewStatus.ACCEPTED);
            }
            diffManager.statusChanged(LogDiffEvent.CHANGE_REVIEWED);
        }
    };

    private ActionListener clearBtnListener = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
            for(Change c : diffManager.getSelectedChanges()) {
                reviewManager.setReviewStatus(c, ReviewStatus.PENDING);
            }
            diffManager.statusChanged(LogDiffEvent.CHANGE_REVIEWED);
        }
    };

    private ActionListener commitBtnListener = e -> {
        Container owner = SwingUtilities.getAncestorOfClass(Frame.class, editorKit.getOWLWorkspace());
        int answer = JOptionPane.showOptionDialog(owner, "Committing these reviews may involve undoing or redoing previous changes.\n" +
                "Are you sure you would like to proceed?", "Confirm reviews", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, null, null);
        if(answer == JOptionPane.OK_OPTION) {
            List<OWLOntologyChange> changes = reviewManager.getReviewOntologyChanges();
            reviewManager.clearUncommittedReviews();
            enable(false, commitBtn);
            if(!changes.isEmpty()) {
                diffManager.commitChanges(changes);

                VersionedOntologyDocument vont = diffManager.getVersionedOntologyDocument().get();
                String commitComment = JOptionPane.showInputDialog(owner, "Comment for the review: ", "Commit reviews");
                if (vont == null) {
                    JOptionPane.showMessageDialog(owner, "Commit ignored because the ontology is not associated with a server");
                    return;
                }
                if (commitComment == null) {
                    return; // user pressed cancel
                }
                Client client = diffManager.getCurrentClient().get();
                ChangeMetaData metaData = new ChangeMetaData("[Review] " + commitComment);
                try {
                    ClientUtilities.commit(client, metaData, vont);
                } catch (OWLServerException e1) {
                    ProtegeApplication.getErrorLog().logError(e1);
                }
                diffManager.setSelectedCommitToLatest();
            }
            JOptionPane.showMessageDialog(owner, "The reviews have been successfully committed", "Reviews committed", JOptionPane.INFORMATION_MESSAGE);
        }
    };

    private JButton getButton(String text, ActionListener listener) {
        JButton button = new JButton();
        button.setText(text);
        button.setPreferredSize(new Dimension(90, 32));
        button.setFocusable(false);
        button.addActionListener(listener);
        button.setEnabled(false);
        return button;
    }

    private void enable(boolean enable, JComponent... components) {
        for(JComponent c : components) {
            c.setEnabled(enable);
        }
    }

    @Override
    public void dispose() {
        rejectBtn.removeActionListener(rejectBtnListener);
        clearBtn.removeActionListener(clearBtnListener);
        acceptBtn.removeActionListener(acceptBtnListener);
        commitBtn.removeActionListener(commitBtnListener);
        diffManager.removeListener(changeSelectionListener);
    }
}