// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.devkit.inspections;

import com.intellij.ExtensionPoints;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.MoveToPackageFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ui.ListTable;
import com.intellij.codeInspection.ui.ListWrappingTableModel;
import com.intellij.codeInspection.util.InspectionMessage;
import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.diagnostic.ITNReporter;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.LoadingOrder;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.openapi.ui.panel.PanelGridBuilder;
import com.intellij.openapi.util.BuildNumber;
import com.intellij.openapi.util.ClearableLazyValue;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.HtmlBuilder;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.NavigatableAdapter;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiFormatUtil;
import com.intellij.psi.util.PsiFormatUtilBase;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.ui.UI;
import com.intellij.util.xml.*;
import com.intellij.util.xml.highlighting.*;
import com.intellij.util.xml.reflect.DomAttributeChildDescription;
import com.intellij.util.xmlb.annotations.Property;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.XCollection;
import com.intellij.xml.CommonXmlStrings;
import com.intellij.xml.util.IncludedXmlTag;
import com.siyeh.ig.ui.ExternalizableStringSet;
import com.siyeh.ig.ui.UiUtils;
import org.jdom.Element;
import org.jetbrains.annotations.*;
import org.jetbrains.idea.devkit.DevKitBundle;
import org.jetbrains.idea.devkit.dom.Action;
import org.jetbrains.idea.devkit.dom.*;
import org.jetbrains.idea.devkit.dom.impl.PluginPsiClassConverter;
import org.jetbrains.idea.devkit.dom.index.ExtensionPointIndex;
import org.jetbrains.idea.devkit.inspections.quickfix.AddWithTagFix;
import org.jetbrains.idea.devkit.module.PluginModuleType;
import org.jetbrains.idea.devkit.util.DescriptorUtil;
import org.jetbrains.idea.devkit.util.PluginPlatformInfo;
import org.jetbrains.idea.devkit.util.PsiUtil;
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class PluginXmlDomInspection extends DevKitPluginXmlInspectionBase {
  @NonNls
  private static final String PLUGIN_ICON_SVG_FILENAME = "pluginIcon.svg";

  @NonNls
  public static final String DEPENDENCIES_DOC_URL =
    "https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html?from=DevkitPluginXmlInspection";

  public List<String> myRegistrationCheckIgnoreClassList = new ExternalizableStringSet();

  @XCollection
  public List<PluginModuleSet> PLUGINS_MODULES = new ArrayList<>();
  private final ClearableLazyValue<Map<String, PluginModuleSet>> myPluginModuleSetByModuleName = ClearableLazyValue.createAtomic(() -> {
    Map<String, PluginModuleSet> result = new HashMap<>();
    for (PluginModuleSet modulesSet : PLUGINS_MODULES) {
      for (String module : modulesSet.modules) {
        result.put(module, modulesSet);
      }
    }
    return result;
  });

  private static class Holder {
    private static final Pattern BASE_LINE_EXTRACTOR = Pattern.compile("(?:\\p{javaLetter}+-)?(\\d+)(?:\\..*)?");
  }

  private static final int FIRST_BRANCH_SUPPORTING_STAR = 131;

  @NotNull
  @Override
  public JComponent createOptionsPanel() {
    ListTable table = new ListTable(new ListWrappingTableModel(myRegistrationCheckIgnoreClassList, ""));
    JPanel panel =
      UiUtils.createAddRemoveTreeClassChooserPanel(table, DevKitBundle.message("inspections.plugin.xml.add.ignored.class.title"));
    PanelGridBuilder grid = UI.PanelFactory.grid();
    grid.resize().add(
      UI.PanelFactory.panel(panel)
        .withLabel(DevKitBundle.message("inspections.plugin.xml.ignore.classes.title"))
        .moveLabelOnTop()
        .resizeY(true)
    );

    if (ApplicationManager.getApplication().isInternal()) {
      JBTextArea component = new JBTextArea(5, 80);
      component.setText(PLUGINS_MODULES.stream().map(it -> String.join(",", it.modules)).collect(Collectors.joining("\n")));
      component.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) {
          PLUGINS_MODULES.clear();
          for (String line : StringUtil.splitByLines(component.getText())) {
            PluginModuleSet set = new PluginModuleSet();
            set.modules = new LinkedHashSet<>(StringUtil.split(line, ","));
            PLUGINS_MODULES.add(set);
          }
          myPluginModuleSetByModuleName.drop();
        }
      });
      ComponentPanelBuilder pluginModulesPanel =
        UI.PanelFactory.panel(new JBScrollPane(component))
          .withLabel(DevKitBundle.message("inspections.plugin.xml.plugin.modules.label"))
          .moveLabelOnTop()
          .withComment(DevKitBundle.message("inspections.plugin.xml.plugin.modules.description"))
          .resizeY(true);
      grid.add(pluginModulesPanel);
    }

    return grid.createPanel();
  }

  @Override
  public void readSettings(@NotNull Element node) {
    super.readSettings(node);
    myPluginModuleSetByModuleName.drop();
  }

  @Override
  @NotNull
  public String getShortName() {
    return "PluginXmlValidity";
  }

  @Override
  protected void checkDomElement(DomElement element, DomElementAnnotationHolder holder, DomHighlightingHelper helper) {
    super.checkDomElement(element, holder, helper);

    ComponentModuleRegistrationChecker componentModuleRegistrationChecker =
      new ComponentModuleRegistrationChecker(myPluginModuleSetByModuleName, myRegistrationCheckIgnoreClassList, holder);

    if (element instanceof IdeaPlugin) {
      Module module = element.getModule();
      if (module != null) {
        annotateIdeaPlugin((IdeaPlugin)element, holder, module);
        checkJetBrainsPlugin((IdeaPlugin)element, holder, module);
        checkPluginIcon((IdeaPlugin)element, holder, module);
      }
    }
    else if (element instanceof Extension) {
      annotateExtension((Extension)element, holder, componentModuleRegistrationChecker);
    }
    else if (element instanceof ExtensionPoint) {
      annotateExtensionPoint((ExtensionPoint)element, holder, componentModuleRegistrationChecker);
    }
    else if (element instanceof Vendor) {
      annotateVendor((Vendor)element, holder);
    }
    else if (element instanceof ProductDescriptor) {
      annotateProductDescriptor((ProductDescriptor)element, holder);
    }
    else if (element instanceof IdeaVersion) {
      annotateIdeaVersion((IdeaVersion)element, holder);
    }
    else if (element instanceof Dependency) {
      annotateDependency((Dependency)element, holder);
    }
    else if (element instanceof DependencyDescriptor) {
      annotateDependencyDescriptor((DependencyDescriptor)element, holder);
    }
    else if (element instanceof ContentDescriptor) {
      annotateContentDescriptor((ContentDescriptor)element, holder);
    }
    else if (element instanceof Extensions) {
      annotateExtensions((Extensions)element, holder);
    }
    else if (element instanceof Extensions.UnresolvedExtension) {
      annotateUnresolvedExtension((Extensions.UnresolvedExtension)element, holder);
    }
    else if (element instanceof AddToGroup) {
      annotateAddToGroup((AddToGroup)element, holder);
    }
    else if (element instanceof Action) {
      annotateAction((Action)element, holder, componentModuleRegistrationChecker);
    }
    else if (element instanceof Synonym) {
      annotateSynonym((Synonym)element, holder);
    }
    else if (element instanceof Group) {
      annotateGroup((Group)element, holder);
    }
    else if (element instanceof Component) {
      annotateComponent((Component)element, holder, componentModuleRegistrationChecker);
      if (element instanceof Component.Project) {
        annotateProjectComponent((Component.Project)element, holder);
      }
    }
    else
      if (element instanceof Helpset) {
      highlightRedundant(element, DevKitBundle.message("inspections.plugin.xml.deprecated.helpset"), holder);
    }
    else if (element instanceof Listeners) {
      annotateListeners((Listeners)element, holder);
    }
    else if (element instanceof Listeners.Listener) {
      annotateListener((Listeners.Listener)element, holder);
    }

    if (element instanceof GenericDomValue) {
      final GenericDomValue domValue = (GenericDomValue)element;

      if (domValue.getConverter() instanceof PluginPsiClassConverter) {
        annotatePsiClassValue(domValue, holder);
      }
    }
  }

  private static void annotateDependencyDescriptor(DependencyDescriptor descriptor, DomElementAnnotationHolder holder) {
    if (!isIdeaProjectOrJetBrains(descriptor)) {
      highlightJetbrainsOnly(descriptor, holder);
      return;
    }

    if (descriptor.getModuleEntry().isEmpty() &&
        descriptor.getPlugin().isEmpty()) {
      holder.createProblem(descriptor, HighlightSeverity.ERROR,
                           DevKitBundle.message("inspections.plugin.xml.dependency.descriptor.at.least.one.dependency"));
      return;
    }

    final IdeaPlugin ideaPlugin = descriptor.getParentOfType(IdeaPlugin.class, false);
    assert ideaPlugin != null;
    for (Dependency dependency : ideaPlugin.getDepends()) {
      if (dependency.getOptional().getValue() == Boolean.TRUE) continue;
      holder.createProblem(dependency, HighlightSeverity.ERROR,
                           DevKitBundle.message("inspections.plugin.xml.dependency.descriptor.cannot.use.depends")).highlightWholeElement();
    }
  }

  private static void annotateContentDescriptor(ContentDescriptor descriptor, DomElementAnnotationHolder holder) {
    if (!isIdeaProjectOrJetBrains(descriptor)) {
      highlightJetbrainsOnly(descriptor, holder);
      return;
    }

    if (descriptor.getModuleEntry().isEmpty()) {
      holder.createProblem(descriptor, HighlightSeverity.ERROR,
                           DevKitBundle.message("inspections.plugin.xml.module.descriptor.at.least.one.dependency"));
    }
  }

  private static boolean isIdeaProjectOrJetBrains(DomElement element) {
    final Module module = element.getModule();
    if (module == null) return true;

    if (PsiUtil.isIdeaProject(module.getProject())) return true;

    final IdeaPlugin ideaPlugin = element.getParentOfType(IdeaPlugin.class, false);
    assert ideaPlugin != null;
    return PluginManagerCore.isDevelopedByJetBrains(ideaPlugin.getVendor().getValue());
  }

  private static void annotatePsiClassValue(GenericDomValue domValue, DomElementAnnotationHolder holder) {
    final Object value = domValue.getValue();
    if (value instanceof PsiClass) {
      PsiClass psiClass = (PsiClass)value;
      if (psiClass.getContainingClass() != null &&
          !StringUtil.containsChar(StringUtil.notNullize(domValue.getRawText()), '$')) {
        holder.createProblem(domValue, DevKitBundle.message("inspections.plugin.xml.inner.class.must.be.separated.with.dollar"));
      }

      Module module = domValue.getModule();
      if (module == null) return;

      if (!isIdeaProjectOrJetBrains(domValue)) return;

      IdeaPlugin ideaPlugin = domValue.getParentOfType(IdeaPlugin.class, true);
      assert ideaPlugin != null;
      String pluginPackage = ideaPlugin.getPackage().getStringValue();
      if (pluginPackage == null) return;

      final String psiClassFqn = psiClass.getQualifiedName();
      assert psiClassFqn != null;

      // only highlight if located in same module
      if (!StringUtil.startsWith(psiClassFqn, pluginPackage + ".") &&
          module == ModuleUtilCore.findModuleForPsiElement(psiClass)) {
        holder.createProblem(domValue, HighlightSeverity.ERROR,
                             DevKitBundle.message("inspections.plugin.xml.dependency.class.located.in.wrong.package",
                                                  psiClassFqn, pluginPackage),
                             new MoveToPackageFix(psiClass.getContainingFile(), pluginPackage));
      }
    }
  }

  private static boolean isUnderProductionSources(DomElement domElement, @NotNull Module module) {
    VirtualFile virtualFile = DomUtil.getFile(domElement).getVirtualFile();
    return virtualFile != null &&
           ModuleRootManager.getInstance(module).getFileIndex().isUnderSourceRootOfType(virtualFile, JavaModuleSourceRootTypes.PRODUCTION);
  }

  private static void annotateListener(Listeners.Listener listener, DomElementAnnotationHolder holder) {
    final PsiClass listenerClass = listener.getListenerClassName().getValue();
    final PsiClass topicClass = listener.getTopicClassName().getValue();
    if (listenerClass == null || topicClass == null) return;

    if (!listenerClass.isInheritor(topicClass, true)) {
      holder.createProblem(listener.getListenerClassName(),
                           DevKitBundle.message("inspections.plugin.xml.listener.does.not.inherit",
                                                listener.getListenerClassName().getStringValue(),
                                                listener.getTopicClassName().getStringValue()));
    }
  }

  private static final int LISTENERS_PLATFORM_VERSION = 193;
  private static final int LISTENERS_OS_ATTRIBUTE_PLATFORM_VERSION = 201;

  private static void annotateListeners(Listeners listeners, DomElementAnnotationHolder holder) {
    final Module module = listeners.getModule();
    if (module == null || PsiUtil.isIdeaProject(module.getProject())) return;

    PluginPlatformInfo platformInfo = PluginPlatformInfo.forDomElement(listeners);
    final PluginPlatformInfo.PlatformResolveStatus resolveStatus = platformInfo.getResolveStatus();

    if (resolveStatus == PluginPlatformInfo.PlatformResolveStatus.DEVKIT_NO_MAIN) {
      holder.createProblem(listeners, ProblemHighlightType.ERROR,
                           DevKitBundle.message("inspections.plugin.xml.since.build.could.not.locate.main.descriptor"), null)
        .highlightWholeElement();
      return;
    }

    if (resolveStatus == PluginPlatformInfo.PlatformResolveStatus.DEVKIT_NO_SINCE_BUILD) {
      final boolean noSinceBuildXml = !DomUtil.hasXml(platformInfo.getMainIdeaPlugin().getIdeaVersion().getSinceBuild());
      holder.createProblem(listeners, ProblemHighlightType.ERROR,
                           DevKitBundle.message("inspections.plugin.xml.since.build.must.be.specified"), null,
                           noSinceBuildXml ? new AddDomElementQuickFix<>(platformInfo.getMainIdeaPlugin().getIdeaVersion()) : null)
        .highlightWholeElement();
      return;
    }

    final BuildNumber buildNumber = platformInfo.getSinceBuildNumber();
    if (buildNumber == null) {
      holder.createProblem(listeners, ProblemHighlightType.ERROR,
                           DevKitBundle.message("inspections.plugin.xml.since.build.could.not.determine.platform.version"), null)
        .highlightWholeElement();
      return;
    }

    final int baselineVersion = buildNumber.getBaselineVersion();

    boolean canHaveOsAttribute = baselineVersion >= LISTENERS_OS_ATTRIBUTE_PLATFORM_VERSION;
    if (!canHaveOsAttribute) {
      for (Listeners.Listener listener : listeners.getListeners()) {
        if (DomUtil.hasXml(listener.getOs())) {
          holder.createProblem(listener.getOs(), ProblemHighlightType.ERROR,
                               DevKitBundle.message("inspections.plugin.xml.since.build.listeners.os.attribute",
                                                    LISTENERS_OS_ATTRIBUTE_PLATFORM_VERSION,
                                                    baselineVersion),
                               null)
            .highlightWholeElement();
        }
      }
    }


    if (baselineVersion >= LISTENERS_PLATFORM_VERSION) return;

    holder.createProblem(listeners, ProblemHighlightType.ERROR,
                         DevKitBundle.message("inspections.plugin.xml.since.build.listeners.not.available",
                                              LISTENERS_PLATFORM_VERSION,
                                              baselineVersion),
                         null)
      .highlightWholeElement();
  }

  private static void annotateDependency(Dependency dependency, DomElementAnnotationHolder holder) {
    final GenericAttributeValue<Boolean> optional = dependency.getOptional();
    if (optional.getValue() == Boolean.FALSE) {
      highlightRedundant(optional,
                         DevKitBundle.message("inspections.plugin.xml.dependency.superfluous.optional"),
                         ProblemHighlightType.WARNING, holder);
    }
    else if (optional.getValue() == Boolean.TRUE &&
             !DomUtil.hasXml(dependency.getConfigFile())) {
      holder.createProblem(dependency, DevKitBundle.message("inspections.plugin.xml.dependency.specify.config.file"),
                           new AddDomElementQuickFix<>(dependency.getConfigFile())).highlightWholeElement();
    }
  }

  private static void annotateIdeaPlugin(IdeaPlugin ideaPlugin, DomElementAnnotationHolder holder, @NotNull Module module) {
    //noinspection deprecation
    highlightAttributeNotUsedAnymore(ideaPlugin.getIdeaPluginVersion(), holder);
    //noinspection deprecation
    if (DomUtil.hasXml(ideaPlugin.getUseIdeaClassloader())) {
      //noinspection deprecation
      highlightDeprecated(ideaPlugin.getUseIdeaClassloader(), DevKitBundle.message("inspections.plugin.xml.deprecated"), holder, true, true);
    }

    checkMaxLength(ideaPlugin.getUrl(), 255, holder);

    checkMaxLength(ideaPlugin.getId(), 255, holder);

    checkTemplateText(ideaPlugin.getName(), "Plugin display name here", holder);
    checkTemplateTextContainsWord(ideaPlugin.getName(), holder, "plugin", "IntelliJ", "JetBrains");
    checkMaxLength(ideaPlugin.getName(), 255, holder);


    checkMaxLength(ideaPlugin.getDescription(), 65535, holder);
    checkHasRealText(ideaPlugin.getDescription(), 40, holder);
    checkTemplateTextContains(ideaPlugin.getDescription(), "Enter short description for your plugin here.", holder);
    checkTemplateTextContains(ideaPlugin.getDescription(), "most HTML tags may be used", holder);

    checkMaxLength(ideaPlugin.getChangeNotes(), 65535, holder);
    checkTemplateTextContains(ideaPlugin.getChangeNotes(), "Add change notes here", holder);
    checkTemplateTextContains(ideaPlugin.getChangeNotes(), "most HTML tags may be used", holder);

    if (!ideaPlugin.hasRealPluginId()) return;

    MultiMap<String, Dependency> dependencies = MultiMap.create();
    ideaPlugin.getDepends().forEach(dependency -> {
      if (DomUtil.hasXml(dependency.getConfigFile())) {
        dependencies.putValue(dependency.getConfigFile().getStringValue(), dependency);
      }
    });
    for (Map.Entry<String, Collection<Dependency>> entry : dependencies.entrySet()) {
      if (entry.getValue().size() > 1) {
        for (Dependency dependency : entry.getValue()) {
          if (dependency.getXmlTag() instanceof IncludedXmlTag) continue;
          highlightRedundant(dependency, DevKitBundle.message("inspections.plugin.xml.duplicated.dependency", entry.getKey()),
                             ProblemHighlightType.ERROR, holder);
        }
      }
    }


    boolean isNotIdeaProject = !PsiUtil.isIdeaProject(module.getProject());

    if (isNotIdeaProject &&
        !DomUtil.hasXml(ideaPlugin.getVersion()) &&
        PluginModuleType.isOfType(module)) {
      holder.createProblem(ideaPlugin, DevKitBundle.message("inspections.plugin.xml.version.must.be.specified"),
                           new AddMissingMainTag(DevKitBundle.message("inspections.plugin.xml.add.version.tag"), ideaPlugin.getVersion(), ""));
    }
    checkMaxLength(ideaPlugin.getVersion(), 64, holder);


    if (isNotIdeaProject && !DomUtil.hasXml(ideaPlugin.getVendor())) {
      holder.createProblem(ideaPlugin, DevKitBundle.message("inspections.plugin.xml.vendor.must.be.specified"),
                           new AddMissingMainTag(DevKitBundle.message("inspections.plugin.xml.add.vendor.tag"), ideaPlugin.getVendor(), ""));
    }

    if (DomUtil.hasXml(ideaPlugin.getPackage()) && !isIdeaProjectOrJetBrains(ideaPlugin)) {
      highlightJetbrainsOnly(ideaPlugin.getPackage(), holder);
    }
  }

  private static void checkJetBrainsPlugin(IdeaPlugin ideaPlugin, DomElementAnnotationHolder holder, @NotNull Module module) {
    if (!PsiUtil.isIdeaProject(module.getProject())) return;

    if (DomUtil.hasXml(ideaPlugin.getUrl())) {
      String url = ideaPlugin.getUrl().getStringValue();
      if ("https://www.jetbrains.com/idea".equals(url)) {
        highlightRedundant(ideaPlugin.getUrl(),
                           DevKitBundle.message("inspections.plugin.xml.plugin.jetbrains.no.generic.plugin.url"), holder);
      }
    }

    if (!ideaPlugin.hasRealPluginId()) return;

    @NonNls String id = ideaPlugin.getId().getStringValue();
    if (id != null &&
        (StringUtil.startsWith(id, "com.android.") || //NON-NLS
         id.equals("org.jetbrains.android"))) {
      return;
    }

    if (!isUnderProductionSources(ideaPlugin, module)) return;

    final Vendor vendor = ideaPlugin.getVendor();
    if (!DomUtil.hasXml(vendor)) {
      holder.createProblem(DomUtil.getFileElement(ideaPlugin),
                           DevKitBundle.message("inspections.plugin.xml.plugin.should.have.jetbrains.vendor"),
                           new AddMissingMainTag(DevKitBundle.message("inspections.plugin.xml.vendor.specify.jetbrains"),
                                                 vendor, PluginManagerCore.VENDOR_JETBRAINS));
    }
    else if (!PluginManagerCore.isVendorJetBrains(vendor.getValue())) {
      holder.createProblem(vendor, DevKitBundle.message("inspections.plugin.xml.plugin.should.have.jetbrains.vendor"));
    }
    else {
      final String url = vendor.getUrl().getStringValue();
      if (url != null && StringUtil.endsWith(url, "jetbrains.com")) {
        highlightRedundant(vendor.getUrl(),
                           DevKitBundle.message("inspections.plugin.xml.plugin.jetbrains.vendor.no.url", url), holder);
      }
    }

    if (DomUtil.hasXml(vendor.getEmail())) {
      highlightRedundant(vendor.getEmail(),
                         DevKitBundle.message("inspections.plugin.xml.plugin.jetbrains.vendor.no.email"), holder);
    }
    if (DomUtil.hasXml(ideaPlugin.getChangeNotes())) {
      highlightRedundant(ideaPlugin.getChangeNotes(),
                         DevKitBundle.message("inspections.plugin.xml.plugin.jetbrains.no.change.notes"), holder);
    }
    if (DomUtil.hasXml(ideaPlugin.getVersion())) {
      highlightRedundant(ideaPlugin.getVersion(),
                         DevKitBundle.message("inspections.plugin.xml.plugin.jetbrains.no.version"), holder);
    }
    if (DomUtil.hasXml(ideaPlugin.getIdeaVersion())) {
      highlightRedundant(ideaPlugin.getIdeaVersion(),
                         DevKitBundle.message("inspections.plugin.xml.plugin.jetbrains.no.idea.version"), holder);
    }
  }

  private static void checkPluginIcon(IdeaPlugin ideaPlugin, DomElementAnnotationHolder holder, Module module) {
    if (!ideaPlugin.hasRealPluginId()) return;
    if (!isUnderProductionSources(ideaPlugin, module)) return;
    if (Boolean.TRUE == ideaPlugin.getImplementationDetail().getValue()) return;

    Collection<VirtualFile> pluginIconFiles =
      FilenameIndex.getVirtualFilesByName(PLUGIN_ICON_SVG_FILENAME, GlobalSearchScope.moduleScope(module));
    if (pluginIconFiles.isEmpty()) {
      holder.createProblem(ideaPlugin, ProblemHighlightType.WEAK_WARNING,
                           DevKitBundle.message("inspections.plugin.xml.no.plugin.icon.svg.file", PLUGIN_ICON_SVG_FILENAME),
                           null);
    }
  }

  private static void annotateExtensionPoint(ExtensionPoint extensionPoint,
                                             DomElementAnnotationHolder holder,
                                             ComponentModuleRegistrationChecker componentModuleRegistrationChecker) {
    if (extensionPoint.getWithElements().isEmpty() &&
        !extensionPoint.collectMissingWithTags().isEmpty()) {
      holder.createProblem(extensionPoint, DevKitBundle.message("inspections.plugin.xml.ep.doesnt.have.with"), new AddWithTagFix());
    }

    checkEpBeanClassAndInterface(extensionPoint, holder);
    checkEpNameAndQualifiedName(extensionPoint, holder);

    if (DomUtil.hasXml(extensionPoint.getQualifiedName())) {
      IdeaPlugin ideaPlugin = DomUtil.getParentOfType(extensionPoint, IdeaPlugin.class, true);
      assert ideaPlugin != null;
      final String pluginId = ideaPlugin.getPluginId();
      if (pluginId != null) {
        final String epQualifiedName = extensionPoint.getQualifiedName().getStringValue();
        if (epQualifiedName != null && epQualifiedName.startsWith(pluginId + ".")) {
          holder.createProblem(extensionPoint.getQualifiedName(), ProblemHighlightType.WARNING,
                               DevKitBundle.message("inspections.plugin.xml.ep.qualifiedName.superfluous"), null,
                               new LocalQuickFix() {
                                 @Override
                                 public @IntentionFamilyName @NotNull String getFamilyName() {
                                   return DevKitBundle.message("inspections.plugin.xml.ep.qualifiedName.superfluous.fix");
                                 }

                                 @Override
                                 public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
                                   extensionPoint.getQualifiedName().undefine();
                                   extensionPoint.getName().setStringValue(StringUtil.substringAfter(epQualifiedName, pluginId + "."));
                                 }
                               }).highlightWholeElement();
        }
      }
    }

    Module module = extensionPoint.getModule();
    if (componentModuleRegistrationChecker.isIdeaPlatformModule(module)) {
      componentModuleRegistrationChecker.checkProperModule(extensionPoint);
    }
  }

  private static void checkEpBeanClassAndInterface(ExtensionPoint extensionPoint, DomElementAnnotationHolder holder) {
    boolean hasBeanClass = DomUtil.hasXml(extensionPoint.getBeanClass());
    boolean hasInterface = DomUtil.hasXml(extensionPoint.getInterface());
    if (hasBeanClass && hasInterface) {
      highlightRedundant(extensionPoint.getBeanClass(),
                         DevKitBundle.message("inspections.plugin.xml.ep.both.beanClass.and.interface"),
                         ProblemHighlightType.GENERIC_ERROR, holder);
      highlightRedundant(extensionPoint.getInterface(),
                         DevKitBundle.message("inspections.plugin.xml.ep.both.beanClass.and.interface"),
                         ProblemHighlightType.GENERIC_ERROR, holder);
    }
    else if (!hasBeanClass && !hasInterface) {
      holder.createProblem(extensionPoint, ProblemHighlightType.GENERIC_ERROR,
                           DevKitBundle.message("inspections.plugin.xml.ep.missing.beanClass.and.interface"), null,
                           new AddDomElementQuickFix<>(extensionPoint.getBeanClass()),
                           new AddDomElementQuickFix<>(extensionPoint.getInterface()));
    }
  }


  private static void checkEpNameAndQualifiedName(ExtensionPoint extensionPoint, DomElementAnnotationHolder holder) {
    GenericAttributeValue<String> name = extensionPoint.getName();
    GenericAttributeValue<String> qualifiedName = extensionPoint.getQualifiedName();
    boolean hasName = DomUtil.hasXml(name);
    boolean hasQualifiedName = DomUtil.hasXml(qualifiedName);

    if (hasName && hasQualifiedName) {
      holder.createProblem(extensionPoint, ProblemHighlightType.GENERIC_ERROR,
                           DevKitBundle.message("inspections.plugin.xml.ep.both.name.and.qualifiedName"), null);
    }
    else if (!hasName && !hasQualifiedName) {
      holder.createProblem(extensionPoint, ProblemHighlightType.GENERIC_ERROR,
                           DevKitBundle.message("inspections.plugin.xml.ep.missing.name.and.qualifiedName"), null);
    }

    if (hasQualifiedName) {
      if (!isValidEpName(qualifiedName)) {
        String message = DevKitBundle.message("inspections.plugin.xml.invalid.ep.name.description",
                                              DevKitBundle.message("inspections.plugin.xml.invalid.ep.qualifiedName"),
                                              qualifiedName.getValue());
        holder.createProblem(qualifiedName, ProblemHighlightType.WEAK_WARNING, message, null);
      }
      return;
    }

    if (hasName && !isValidEpName(name)) {
      String message = DevKitBundle.message("inspections.plugin.xml.invalid.ep.name.description",
                                            DevKitBundle.message("inspections.plugin.xml.invalid.ep.name"),
                                            name.getValue());
      holder.createProblem(name, ProblemHighlightType.WEAK_WARNING, message, null);
    }
  }

  private static boolean isValidEpName(GenericAttributeValue<String> nameAttrValue) {
    if (!nameAttrValue.exists()) {
      return true;
    }
    @NonNls String name = nameAttrValue.getValue();

    if (name != null
        && StringUtil.startsWith(name, "Pythonid.") // NON-NLS
        && PsiUtil.isIdeaProject(nameAttrValue.getManager().getProject())) {
      return true;
    }

    if (StringUtil.isEmpty(name) ||
        !Character.isLowerCase(name.charAt(0)) || // also checks that name doesn't start with dot
        StringUtil.toUpperCase(name).equals(name) || // not all uppercase
        !StringUtil.isLatinAlphanumeric(name.replace(".", "")) ||
        name.charAt(name.length() - 1) == '.') {
      return false;
    }

    List<String> fragments = StringUtil.split(name, ".");
    if (fragments.stream().anyMatch(f -> Character.isUpperCase(f.charAt(0)))) {
      return false;
    }

    String epName = fragments.get(fragments.size() - 1);
    fragments.remove(fragments.size() - 1);
    List<String> words = StringUtil.getWordsIn(epName);
    return words.stream().noneMatch(w -> fragments.stream().anyMatch(f -> StringUtil.equalsIgnoreCase(w, f)));
  }

  private static void annotateExtensions(Extensions extensions, DomElementAnnotationHolder holder) {
    final GenericAttributeValue<IdeaPlugin> xmlnsAttribute = extensions.getXmlns();
    if (DomUtil.hasXml(xmlnsAttribute)) {
      highlightDeprecated(xmlnsAttribute, DevKitBundle.message("inspections.plugin.xml.use.defaultExtensionNs"), holder, false, true);
      return;
    }

    if (!DomUtil.hasXml(extensions.getDefaultExtensionNs())) {
      holder.createProblem(
        extensions,
        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
        DevKitBundle.message("inspections.plugin.xml.specify.defaultExtensionNs.explicitly", Extensions.DEFAULT_PREFIX),
        null,
        new AddDomElementQuickFix<GenericAttributeValue>(extensions.getDefaultExtensionNs()) {
          @Override
          public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            super.applyFix(project, descriptor);
            myElement.setStringValue(Extensions.DEFAULT_PREFIX);
          }
        });
    }
  }

  private static void annotateUnresolvedExtension(Extensions.UnresolvedExtension unresolvedExtension, DomElementAnnotationHolder holder) {
    final Module module = unresolvedExtension.getModule();
    if (module == null) return;

    final Extensions extensions = unresolvedExtension.getParentOfType(Extensions.class, true);
    assert extensions != null;
    String qualifiedExtensionId = extensions.getEpPrefix() + unresolvedExtension.getXmlElementName();

    final ExtensionPoint extensionPoint = ExtensionPointIndex.findExtensionPoint(module, qualifiedExtensionId);
    if (extensionPoint == null) {
      String message = new HtmlBuilder()
        .append(DevKitBundle.message("error.cannot.resolve.extension.point", qualifiedExtensionId))
        .nbsp()
        .append(HtmlChunk.link(DEPENDENCIES_DOC_URL, DevKitBundle.message("error.cannot.resolve.plugin.reference.link.title")))
        .wrapWith(HtmlChunk.html()).toString();

      holder.createProblem(unresolvedExtension, ProblemHighlightType.LIKE_UNKNOWN_SYMBOL, message, null);
      return;
    }


    final IdeaPlugin ideaPlugin = extensionPoint.getParentOfType(IdeaPlugin.class, true);
    assert ideaPlugin != null;
    String dependencyId = ideaPlugin.getPluginId();

    final LocalQuickFix addDependsFix = new LocalQuickFix() {
      @Override
      public @IntentionFamilyName @NotNull String getFamilyName() {
        return DevKitBundle.message("error.cannot.resolve.extension.point.missing.dependency.fix.family.name");
      }

      @Override
      public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        final IdeaPlugin ideaPlugin = extensions.getParentOfType(IdeaPlugin.class, true);
        assert ideaPlugin != null;
        final Dependency dependency = ideaPlugin.addDependency();
        dependency.setStringValue(dependencyId);
      }
    };
    holder.createProblem(unresolvedExtension,
                         DevKitBundle.message("error.cannot.resolve.extension.point.missing.dependency", qualifiedExtensionId),
                         addDependsFix);
  }

  private static void annotateIdeaVersion(IdeaVersion ideaVersion, DomElementAnnotationHolder holder) {
    //noinspection deprecation
    highlightAttributeNotUsedAnymore(ideaVersion.getMin(), holder);
    //noinspection deprecation
    highlightAttributeNotUsedAnymore(ideaVersion.getMax(), holder);
    highlightUntilBuild(ideaVersion, holder);

    GenericAttributeValue<BuildNumber> sinceBuild = ideaVersion.getSinceBuild();
    GenericAttributeValue<BuildNumber> untilBuild = ideaVersion.getUntilBuild();
    if (!DomUtil.hasXml(sinceBuild) &&
        !DomUtil.hasXml(untilBuild)) {
      return;
    }

    BuildNumber sinceBuildNumber = sinceBuild.getValue();
    BuildNumber untilBuildNumber = untilBuild.getValue();
    if (sinceBuildNumber == null || untilBuildNumber == null) return;

    int compare = Comparing.compare(sinceBuildNumber, untilBuildNumber);
    if (compare > 0) {
      holder.createProblem(untilBuild, DevKitBundle.message("inspections.plugin.xml.until.build.must.be.greater.than.since.build"));
    }
  }

  private static void highlightUntilBuild(IdeaVersion ideaVersion, DomElementAnnotationHolder holder) {
    String untilBuild = ideaVersion.getUntilBuild().getStringValue();
    if (untilBuild != null && isStarSupported(ideaVersion.getSinceBuild().getStringValue())) {
      Matcher matcher = PluginManager.EXPLICIT_BIG_NUMBER_PATTERN.matcher(untilBuild);
      if (matcher.matches()) {
        holder.createProblem(
          ideaVersion.getUntilBuild(),
          DevKitBundle.message("inspections.plugin.xml.until.build.use.asterisk.instead.of.big.number", matcher.group(2)),
          new CorrectUntilBuildAttributeFix(PluginManager.convertExplicitBigNumberInUntilBuildToStar(untilBuild)));
      }
      if (untilBuild.matches("\\d+")) {
        int branch = Integer.parseInt(untilBuild);
        String corrected = (branch - 1) + ".*";
        holder.createProblem(ideaVersion.getUntilBuild(),
                             DevKitBundle.message("inspections.plugin.xml.until.build.misleading.plain.number", untilBuild, corrected),
                             new CorrectUntilBuildAttributeFix(corrected));
      }
    }
  }

  private static boolean isStarSupported(String buildNumber) {
    if (buildNumber == null) return false;
    Matcher matcher = Holder.BASE_LINE_EXTRACTOR.matcher(buildNumber);
    if (matcher.matches()) {
      int branch = Integer.parseInt(matcher.group(1));
      return branch >= FIRST_BRANCH_SUPPORTING_STAR;
    }
    return false;
  }

  private static void annotateExtension(Extension extension,
                                        DomElementAnnotationHolder holder,
                                        ComponentModuleRegistrationChecker componentModuleRegistrationChecker) {
    final ExtensionPoint extensionPoint = extension.getExtensionPoint();
    if (extensionPoint == null) return;
    final Module module = extension.getModule();

    final String effectiveQualifiedName = extensionPoint.getEffectiveQualifiedName();

    final ExtensionPoint.Status status = extensionPoint.getExtensionPointStatus();
    ExtensionPoint.Status.Kind kind = status.getKind();
    if (kind == ExtensionPoint.Status.Kind.SCHEDULED_FOR_REMOVAL_API) {
      final String inVersion = status.getAdditionalData();
      String message = inVersion == null ?
                       DevKitBundle.message("inspections.plugin.xml.deprecated.ep.marked.for.removal",
                                            effectiveQualifiedName) :
                       DevKitBundle.message("inspections.plugin.xml.deprecated.ep.marked.for.removal.in.version",
                                            effectiveQualifiedName, inVersion);
      highlightDeprecatedMarkedForRemoval(
        extension, message,
        holder, false, false
      );
    }
    else if (kind == ExtensionPoint.Status.Kind.DEPRECATED) {
      highlightDeprecated(
        extension, DevKitBundle.message("inspections.plugin.xml.deprecated.ep", effectiveQualifiedName),
        holder, false, false);
    }
    else if (kind == ExtensionPoint.Status.Kind.ADDITIONAL_DEPRECATED && module != null) {
      final String knownReplacementEp = status.getAdditionalData();
      if (knownReplacementEp == null) {
        highlightDeprecated(
          extension, DevKitBundle.message("inspections.plugin.xml.deprecated.ep", effectiveQualifiedName),
          holder, false, false);
      }
      else if (ExtensionPointIndex.findExtensionPoint(module, knownReplacementEp) != null) {
        highlightDeprecated(
          extension,
          DevKitBundle.message("inspections.plugin.xml.deprecated.ep.use.replacement", effectiveQualifiedName, knownReplacementEp),
          holder, false, false);
      }
    }
    else if (kind == ExtensionPoint.Status.Kind.EXPERIMENTAL_API) {
      highlightExperimental(extension, holder);
    }
    else if (kind == ExtensionPoint.Status.Kind.INTERNAL_API &&
             module != null && !PsiUtil.isIdeaProject(module.getProject())) {
      highlightInternal(extension, holder);
    }

    if (ExtensionPoints.ERROR_HANDLER_EP.getName().equals(effectiveQualifiedName)) {
      String implementation = extension.getXmlTag().getAttributeValue("implementation");
      if (ITNReporter.class.getName().equals(implementation)) {
        IdeaPlugin plugin = extension.getParentOfType(IdeaPlugin.class, true);
        if (plugin != null) {
          Vendor vendor = plugin.getVendor();
          if (DomUtil.hasXml(vendor) && PluginManagerCore.isDevelopedByJetBrains(vendor.getValue())) {
            highlightRedundant(extension,
                               DevKitBundle.message("inspections.plugin.xml.no.need.to.specify.itnReporter"),
                               ProblemHighlightType.LIKE_UNUSED_SYMBOL, holder);
          }
          else {
            boolean inPlatformCode = module != null && module.getName().startsWith("intellij.platform.");
            if (!inPlatformCode) {
              highlightRedundant(extension,
                                 DevKitBundle.message("inspections.plugin.xml.third.party.plugins.must.not.use.itnReporter"),
                                 holder);
            }
          }
        }
      }
    }


    if (ServiceDescriptor.class.getName().equals(extensionPoint.getBeanClass().getStringValue())) {
      GenericAttributeValue serviceInterface = getAttribute(extension, "serviceInterface");
      GenericAttributeValue serviceImplementation = getAttribute(extension, "serviceImplementation");
      if (serviceInterface != null && serviceImplementation != null &&
          StringUtil.equals(serviceInterface.getStringValue(), serviceImplementation.getStringValue())) {
        GenericAttributeValue<?> testServiceImplementation = getAttribute(extension, "testServiceImplementation");
        if (testServiceImplementation != null &&
            !DomUtil.hasXml(testServiceImplementation)) {
          highlightRedundant(serviceInterface,
                             DevKitBundle.message("inspections.plugin.xml.service.interface.class.redundant"),
                             ProblemHighlightType.WARNING, holder);
        }
      }
    }

    final List<? extends DomAttributeChildDescription> descriptions = extension.getGenericInfo().getAttributeChildrenDescriptions();
    for (DomAttributeChildDescription attributeDescription : descriptions) {
      final GenericAttributeValue attributeValue = attributeDescription.getDomAttributeValue(extension);
      if (attributeValue == null || !DomUtil.hasXml(attributeValue)) continue;

      // IconsReferencesContributor
      if ("icon".equals(attributeDescription.getXmlElementName())) {
        annotateResolveProblems(holder, attributeValue);
      }
      else if ("order".equals(attributeDescription.getXmlElementName())) {
        annotateOrderAttributeProblems(holder, attributeValue);
      }

      final PsiElement declaration = attributeDescription.getDeclaration(extension.getManager().getProject());
      if (declaration instanceof PsiField) {
        PsiField psiField = (PsiField)declaration;
        if (psiField.isDeprecated()) {
          highlightDeprecated(
            attributeValue, DevKitBundle.message("inspections.plugin.xml.deprecated.attribute", attributeDescription.getName()),
            holder, false, true);
        }
        else if (psiField.hasAnnotation(ApiStatus.Experimental.class.getCanonicalName())) {
          highlightExperimental(attributeValue, holder);
        }
        else if (psiField.hasAnnotation(ApiStatus.Internal.class.getCanonicalName()) &&
                 module != null && !PsiUtil.isIdeaProject(module.getProject())) {
          highlightInternal(attributeValue, holder);
        }
      }
    }

    if (componentModuleRegistrationChecker.isIdeaPlatformModule(module)) {
      componentModuleRegistrationChecker.checkProperXmlFileForExtension(extension);
    }
  }

  private static void annotateComponent(Component component,
                                        DomElementAnnotationHolder holder,
                                        ComponentModuleRegistrationChecker componentModuleRegistrationChecker) {
    Module module = component.getModule();
    if (componentModuleRegistrationChecker.isIdeaPlatformModule(module)) {
      componentModuleRegistrationChecker.checkProperXmlFileForClass(
        component, component.getImplementationClass().getValue());
    }

    GenericDomValue<PsiClass> interfaceClassElement = component.getInterfaceClass();
    PsiClass interfaceClass = interfaceClassElement.getValue();
    if (interfaceClass != null && interfaceClass.equals(component.getImplementationClass().getValue()) &&
        component.getHeadlessImplementationClass().getValue() == null) {
      highlightRedundant(interfaceClassElement,
                         DevKitBundle.message("inspections.plugin.xml.component.interface.class.redundant"),
                         ProblemHighlightType.WARNING, holder);
    }
  }

  private static void annotateVendor(Vendor vendor, DomElementAnnotationHolder holder) {
    //noinspection deprecation
    highlightAttributeNotUsedAnymore(vendor.getLogo(), holder);

    checkTemplateText(vendor, "YourCompany", holder);
    checkMaxLength(vendor, 255, holder);

    //noinspection HttpUrlsUsage
    checkTemplateText(vendor.getUrl(), "http://www.yourcompany.com", holder);
    checkMaxLength(vendor.getUrl(), 255, holder);

    checkTemplateText(vendor.getEmail(), "support@yourcompany.com", holder);
    checkMaxLength(vendor.getEmail(), 255, holder);
  }

  private static void annotateProductDescriptor(ProductDescriptor productDescriptor, DomElementAnnotationHolder holder) {
    checkMaxLength(productDescriptor.getCode(), 15, holder);

    String releaseDate = productDescriptor.getReleaseDate().getValue();
    if (releaseDate != null && !isPlaceHolder(releaseDate)) {
      try {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
        dateFormat.setLenient(false);
        dateFormat.parse(releaseDate);
      }
      catch (ParseException e) {
        holder.createProblem(productDescriptor.getReleaseDate(),
                             DevKitBundle.message("inspections.plugin.xml.product.descriptor.invalid.date"));
      }
    }
    String version = productDescriptor.getReleaseVersion().getValue();
    if (version != null && !isPlaceHolder(version)) {
      try {
        Integer.parseInt(version);
      }
      catch (NumberFormatException e) {
        holder.createProblem(productDescriptor.getReleaseVersion(),
                             DevKitBundle.message("inspections.plugin.xml.product.descriptor.invalid.version"));
      }
    }
  }

  private static boolean isPlaceHolder(@Nullable String value) {
    return value != null && value.length() > 4 && value.startsWith("__") && value.endsWith("__");
  }

  private static void annotateAddToGroup(AddToGroup addToGroup, DomElementAnnotationHolder holder) {
    if (!DomUtil.hasXml(addToGroup.getRelativeToAction())) return;

    if (!DomUtil.hasXml(addToGroup.getAnchor())) {
      holder.createProblem(addToGroup, DevKitBundle.message("inspections.plugin.xml.anchor.must.have.relative-to-action"),
                           new AddDomElementQuickFix<GenericAttributeValue>(addToGroup.getAnchor()));
      return;
    }

    final Anchor value = addToGroup.getAnchor().getValue();
    if (value == Anchor.after || value == Anchor.before) {
      return;
    }
    holder.createProblem(
      addToGroup.getAnchor(),
      DevKitBundle.message("inspections.plugin.xml.must.use.after.before.with.relative-to-action", Anchor.after, Anchor.before));
  }

  private static void annotateGroup(Group group, DomElementAnnotationHolder holder) {
    final GenericAttributeValue<String> iconAttribute = group.getIcon();
    if (DomUtil.hasXml(iconAttribute)) {
      annotateResolveProblems(holder, iconAttribute);
    }

    GenericAttributeValue<PsiClass> clazz = group.getClazz();
    if (DomUtil.hasXml(clazz) &&
        !DomUtil.hasXml(group.getId())) {
      holder.createProblem(group, ProblemHighlightType.WARNING, DevKitBundle.message("inspections.plugin.xml.action.group.id.required"),
                           null, new AddDomElementQuickFix<>(group.getId()));
    }


    GenericAttributeValue<ActionOrGroup> useShortcutOfAttribute = group.getUseShortcutOf();
    if (!DomUtil.hasXml(useShortcutOfAttribute)) return;

    if (!DomUtil.hasXml(clazz)) {
      holder.createProblem(group, DevKitBundle.message("inspections.plugin.xml.action.class.required.with.use.shortcut.of"),
                           new AddDomElementQuickFix<GenericAttributeValue>(group.getClazz()));
      return;
    }

    PsiClass actionGroupClass = clazz.getValue();
    if (actionGroupClass == null) return;

    PsiMethod canBePerformedMethod = new LightMethodBuilder(actionGroupClass.getManager(), "canBePerformed")
      .setContainingClass(JavaPsiFacade.getInstance(actionGroupClass.getProject()).findClass(ActionGroup.class.getName(),
                                                                                             actionGroupClass.getResolveScope()))
      .setModifiers(PsiModifier.PUBLIC)
      .setMethodReturnType(PsiType.BOOLEAN)
      .addParameter("context", DataContext.class.getName());

    PsiMethod overriddenCanBePerformedMethod = actionGroupClass.findMethodBySignature(canBePerformedMethod, false);
    if (overriddenCanBePerformedMethod == null) {
      String methodPresentation = PsiFormatUtil.formatMethod(canBePerformedMethod, PsiSubstitutor.EMPTY,
                                                             PsiFormatUtilBase.SHOW_NAME |
                                                             PsiFormatUtilBase.SHOW_PARAMETERS |
                                                             PsiFormatUtilBase.SHOW_CONTAINING_CLASS,
                                                             PsiFormatUtilBase.SHOW_TYPE);
      holder.createProblem(clazz,
                           DevKitBundle.message("inspections.plugin.xml.action.must.override.method.with.use.shortcut.of",
                                                methodPresentation));
    }
  }

  private static void annotateAction(Action action,
                                     DomElementAnnotationHolder holder,
                                     ComponentModuleRegistrationChecker componentModuleRegistrationChecker) {
    final GenericAttributeValue<String> iconAttribute = action.getIcon();
    if (DomUtil.hasXml(iconAttribute)) {
      annotateResolveProblems(holder, iconAttribute);
    }
    Module module = action.getModule();
    if (componentModuleRegistrationChecker.isIdeaPlatformModule(module)) {
      componentModuleRegistrationChecker.checkProperXmlFileForClass(action, action.getClazz().getValue());
    }
  }

  private static void annotateSynonym(Synonym synonym, DomElementAnnotationHolder holder) {
    boolean hasKey = DomUtil.hasXml(synonym.getKey());
    boolean hasText = DomUtil.hasXml(synonym.getText());

    if (!hasKey && !hasText) {
      holder.createProblem(synonym, ProblemHighlightType.GENERIC_ERROR,
                           DevKitBundle.message("inspections.plugin.xml.synonym.missing.key.and.text"), null,
                           new AddDomElementQuickFix<>(synonym.getKey()),
                           new AddDomElementQuickFix<>(synonym.getText()));
    }
    else if (hasKey && hasText) {
      holder.createProblem(synonym, ProblemHighlightType.GENERIC_ERROR,
                           DevKitBundle.message("inspections.plugin.xml.synonym.both.key.and.text"), null);
    }
  }

  private static void annotateProjectComponent(Component.Project projectComponent, DomElementAnnotationHolder holder) {
    //noinspection deprecation
    GenericDomValue<Boolean> skipForDefault = projectComponent.getSkipForDefaultProject();
    if (skipForDefault.exists()) {
      highlightDeprecated(skipForDefault, DevKitBundle.message("inspections.plugin.xml.skipForDefaultProject.deprecated"),
                          holder, true, true);
    }
  }

  private static void annotateOrderAttributeProblems(DomElementAnnotationHolder holder, GenericAttributeValue attributeValue) {
    String orderValue = attributeValue.getStringValue();
    if (StringUtil.isEmpty(orderValue)) {
      return;
    }

    try {
      LoadingOrder.readOrder(orderValue);
    }
    catch (AssertionError ignore) {
      holder.createProblem(attributeValue, HighlightSeverity.ERROR, DevKitBundle.message("inspections.plugin.xml.invalid.order.attribute"));
      return; // no need to resolve references for invalid attribute value
    }

    annotateResolveProblems(holder, attributeValue);
  }

  private static void annotateResolveProblems(DomElementAnnotationHolder holder, GenericAttributeValue attributeValue) {
    final XmlAttributeValue value = attributeValue.getXmlAttributeValue();
    if (value != null) {
      for (PsiReference reference : value.getReferences()) {
        if (reference.resolve() == null) {
          holder.createResolveProblem(attributeValue, reference);
        }
      }
    }
  }

  private static void highlightRedundant(DomElement element, @InspectionMessage String message, DomElementAnnotationHolder holder) {
    highlightRedundant(element, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, holder);
  }

  private static void highlightRedundant(DomElement element,
                                         @InspectionMessage String message,
                                         ProblemHighlightType highlightType,
                                         DomElementAnnotationHolder holder) {
    holder.createProblem(element, highlightType, message, null, new RemoveDomElementQuickFix(element)).highlightWholeElement();
  }

  private static void highlightAttributeNotUsedAnymore(GenericAttributeValue attributeValue,
                                                       DomElementAnnotationHolder holder) {
    if (!DomUtil.hasXml(attributeValue)) return;
    highlightDeprecated(
      attributeValue, DevKitBundle.message("inspections.plugin.xml.attribute.not.used.anymore", attributeValue.getXmlElementName()),
      holder, true, true);
  }

  private static void highlightDeprecated(DomElement element, @InspectionMessage String message, DomElementAnnotationHolder holder,
                                          boolean useRemoveQuickfix, boolean highlightWholeElement) {
    doHighlightDeprecatedElement(element, message, holder, useRemoveQuickfix, highlightWholeElement, false);
  }

  private static void highlightDeprecatedMarkedForRemoval(DomElement element, @InspectionMessage String message,
                                                          DomElementAnnotationHolder holder,
                                                          boolean useRemoveQuickfix, boolean highlightWholeElement) {
    doHighlightDeprecatedElement(element, message, holder, useRemoveQuickfix, highlightWholeElement, true);
  }

  private static void doHighlightDeprecatedElement(DomElement element,
                                                   @InspectionMessage String message,
                                                   DomElementAnnotationHolder holder,
                                                   boolean useRemoveQuickfix,
                                                   boolean highlightWholeElement,
                                                   boolean forRemoval) {
    DomElementProblemDescriptor problem;
    ProblemHighlightType problemHighlightType = forRemoval ? ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL :
                                                ProblemHighlightType.LIKE_DEPRECATED;
    if (!useRemoveQuickfix) {
      problem = holder.createProblem(element, problemHighlightType, message, null);
    }
    else {
      problem = holder.createProblem(element, problemHighlightType, message, null, new RemoveDomElementQuickFix(element));
    }
    if (highlightWholeElement) {
      problem.highlightWholeElement();
    }
  }

  private static void highlightJetbrainsOnly(DomElement element, DomElementAnnotationHolder holder) {
    holder.createProblem(element, ProblemHighlightType.WARNING,
                         DevKitBundle.message("inspections.plugin.xml.jetbrains.only.api",
                                              ApiStatus.Experimental.class.getCanonicalName()),
                         null)
      .highlightWholeElement();
  }

  private static void highlightExperimental(DomElement element, DomElementAnnotationHolder holder) {
    holder.createProblem(element, ProblemHighlightType.WARNING,
                         DevKitBundle.message("inspections.plugin.xml.usage.of.experimental.api",
                                              ApiStatus.Experimental.class.getCanonicalName()),
                         null)
      .highlightWholeElement();
  }

  private static void highlightInternal(DomElement element, DomElementAnnotationHolder holder) {
    holder.createProblem(element, ProblemHighlightType.WARNING,
                         DevKitBundle.message("inspections.plugin.xml.usage.of.internal.api",
                                              ApiStatus.Internal.class.getCanonicalName()),
                         null)
      .highlightWholeElement();
  }

  private static void checkTemplateText(GenericDomValue<String> domValue,
                                        @NonNls String templateText,
                                        DomElementAnnotationHolder holder) {
    if (templateText.equals(domValue.getValue())) {
      holder.createProblem(domValue, DevKitBundle.message("inspections.plugin.xml.do.not.use.template.text", templateText));
    }
  }

  private static void checkTemplateTextContains(GenericDomValue<String> domValue,
                                                @NonNls String containsText,
                                                DomElementAnnotationHolder holder) {
    String text = domValue.getStringValue();
    if (text != null && StringUtil.containsIgnoreCase(text, containsText)) {
      holder.createProblem(domValue, DevKitBundle.message("inspections.plugin.xml.must.not.contain.template.text", containsText));
    }
  }

  private static void checkTemplateTextContainsWord(GenericDomValue<String> domValue,
                                                    DomElementAnnotationHolder holder,
                                                    @NonNls String... templateWords) {
    String text = domValue.getStringValue();
    if (text == null) return;
    for (String word : StringUtil.getWordsIn(text)) {
      for (String templateWord : templateWords) {
        if (StringUtil.equalsIgnoreCase(word, templateWord)) {
          holder.createProblem(domValue, DevKitBundle.message("inspections.plugin.xml.must.not.contain.template.text", templateWord));
        }
      }
    }
  }

  private static void checkMaxLength(GenericDomValue<String> domValue,
                                     int maxLength,
                                     DomElementAnnotationHolder holder) {
    String value = domValue.getStringValue();
    if (value != null && value.length() > maxLength) {
      holder.createProblem(domValue, DevKitBundle.message("inspections.plugin.xml.value.exceeds.max.length", maxLength));
    }
  }

  private static void checkHasRealText(GenericDomValue<String> domValue,
                                       int minimumLength,
                                       DomElementAnnotationHolder holder) {
    if (!DomUtil.hasXml(domValue)) return;

    String value = StringUtil.removeHtmlTags(StringUtil.notNullize(domValue.getStringValue()));
    value = StringUtil.replace(value, CommonXmlStrings.CDATA_START, "");
    value = StringUtil.replace(value, CommonXmlStrings.CDATA_END, "");

    if (StringUtil.isEmptyOrSpaces(value) || value.length() < minimumLength) {
      holder.createProblem(domValue, DevKitBundle.message("inspections.plugin.xml.value.must.have.minimum.length", minimumLength));
    }
  }

  private static class CorrectUntilBuildAttributeFix implements LocalQuickFix {
    private static final Logger LOG = Logger.getInstance(PluginXmlDomInspection.class);

    private final String myCorrectValue;

    CorrectUntilBuildAttributeFix(String correctValue) {
      myCorrectValue = correctValue;
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return DevKitBundle.message("inspections.plugin.xml.change.until.build.name", myCorrectValue);
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return DevKitBundle.message("inspections.plugin.xml.change.until.build.family.name");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final XmlAttribute attribute = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), XmlAttribute.class, false);
      GenericAttributeValue<?> domElement = DomManager.getDomManager(project).getDomElement(attribute);
      LOG.assertTrue(domElement != null);
      domElement.setStringValue(myCorrectValue);
    }
  }

  private static final class AddMissingMainTag implements LocalQuickFix {

    @IntentionFamilyName
    @NotNull
    private final String myFamilyName;

    @NotNull
    private final String myTagName;

    @Nullable
    private final String myTagValue;

    private AddMissingMainTag(@IntentionFamilyName @NotNull String familyName,
                              @NotNull GenericDomValue domValue,
                              @Nullable String tagValue) {
      myFamilyName = familyName;
      myTagName = domValue.getXmlElementName();
      myTagValue = tagValue;
    }

    @IntentionFamilyName
    @NotNull
    @Override
    public String getFamilyName() {
      return myFamilyName;
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      PsiFile file = descriptor.getPsiElement().getContainingFile();
      IdeaPlugin root = DescriptorUtil.getIdeaPlugin((XmlFile)file);
      if (root != null) {
        XmlTag after = getLastSubTag(root, root.getId(), root.getDescription(), root.getVersion(), root.getName());
        XmlTag rootTag = root.getXmlTag();
        XmlTag missingTag = rootTag.createChildTag(myTagName, rootTag.getNamespace(), myTagValue, false);

        XmlTag addedTag;
        if (after == null) {
          addedTag = rootTag.addSubTag(missingTag, true);
        }
        else {
          addedTag = (XmlTag)rootTag.addAfter(missingTag, after);
        }

        if (StringUtil.isEmpty(myTagValue)) {
          int valueStartOffset = addedTag.getValue().getTextRange().getStartOffset();
          NavigatableAdapter.navigate(project, file.getVirtualFile(), valueStartOffset, true);
        }
      }
    }

    private static XmlTag getLastSubTag(IdeaPlugin root, DomElement... children) {
      Set<XmlTag> childrenTags = new HashSet<>();
      for (DomElement child : children) {
        if (child != null) {
          childrenTags.add(child.getXmlTag());
        }
      }
      XmlTag[] subTags = root.getXmlTag().getSubTags();
      for (int i = subTags.length - 1; i >= 0; i--) {
        if (childrenTags.contains(subTags[i])) {
          return subTags[i];
        }
      }
      return null;
    }
  }

  @Tag("modules-set")
  public static class PluginModuleSet {
    @XCollection(elementName = "module", valueAttributeName = "name")
    @Property(surroundWithTag = false)
    public Set<String> modules = new LinkedHashSet<>();
  }
}
