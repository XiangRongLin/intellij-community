
/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.find.actions;

import com.intellij.find.FindBundle;
import com.intellij.find.findInProject.FindInProjectManager;
import com.intellij.ide.lightEdit.LightEdit;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectoryContainer;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public class FindInPathAction extends AnAction implements DumbAware {
  public static final NotificationGroup NOTIFICATION_GROUP = NotificationGroupManager.getInstance().getNotificationGroup("Find in Path");

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    DataContext dataContext = e.getDataContext();
    Project project = e.getData(CommonDataKeys.PROJECT);
    if (project == null) return;

    FindInProjectManager findManager = FindInProjectManager.getInstance(project);
    if (!findManager.isEnabled()) {
      showNotAvailableMessage(e, project);
      return;
    }

    findManager.findInProject(dataContext, null);
  }

  static void showNotAvailableMessage(AnActionEvent e, Project project) {
    final String message = FindBundle.message("notification.content.not.available.while.search.in.progress", e.getPresentation().getText());
    NOTIFICATION_GROUP.createNotification(message, NotificationType.WARNING).notify(project);
  }

  @Override
  public void update(@NotNull AnActionEvent e){
    doUpdate(e);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  static void doUpdate(@NotNull AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    Project project = e.getData(CommonDataKeys.PROJECT);
    presentation.setEnabled(project != null && !LightEdit.owns(project));
    if (ActionPlaces.isPopupPlace(e.getPlace()) && !e.getPlace().equals(ActionPlaces.ACTION_PLACE_QUICK_LIST_POPUP_ACTION)) {
      presentation.setVisible(isValidSearchScope(e));
    }
  }

  private static boolean isValidSearchScope(@NotNull AnActionEvent e) {
    final PsiElement[] elements = e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY);
    if (elements != null && elements.length == 1 && elements[0] instanceof PsiDirectoryContainer) {
      return true;
    }
    final VirtualFile[] virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    return virtualFiles != null && virtualFiles.length == 1 && virtualFiles[0].isDirectory();
  }
}
