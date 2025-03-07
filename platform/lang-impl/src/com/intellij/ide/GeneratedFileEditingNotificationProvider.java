// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide;

import com.intellij.lang.LangBundle;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.GeneratedSourcesFilter;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GeneratedFileEditingNotificationProvider extends EditorNotifications.Provider<EditorNotificationPanel> implements DumbAware {
  private static final Key<EditorNotificationPanel> KEY = Key.create("generated.source.file.editing.notification.panel");

  @NotNull
  @Override
  public Key<EditorNotificationPanel> getKey() {
    return KEY;
  }

  @Nullable
  @Override
  public EditorNotificationPanel createNotificationPanel(@NotNull VirtualFile file, @NotNull FileEditor fileEditor, @NotNull Project project) {
    if (!GeneratedSourceFileChangeTracker.getInstance(project).isEditedGeneratedFile(file)) return null;

    EditorNotificationPanel panel = new EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Warning);
    panel.setText(getText(file, project));
    return panel;
  }

  private static @NlsContexts.LinkLabel @NotNull String getText(@NotNull VirtualFile file, @NotNull Project project) {
    if (!project.isDisposed() && file.isValid()) {
      for (GeneratedSourcesFilter filter : GeneratedSourcesFilter.EP_NAME.getExtensionList()) {
        if (!filter.isGeneratedSource(file, project)) {
          continue;
        }
        String text = filter.getNotificationText(file, project);
        if (text != null) {
          return text;
        }
      }
    }
    return LangBundle.message("link.label.generated.source.files");
  }
}
