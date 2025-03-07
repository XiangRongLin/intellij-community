// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.tools.projectWizard.cli;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.idea.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.idea.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.idea.base.test.TestRoot;
import org.junit.runner.RunWith;

/**
 * This class is generated by {@link org.jetbrains.kotlin.testGenerator.generator.TestGenerator}.
 * DO NOT MODIFY MANUALLY.
 */
@SuppressWarnings("all")
@TestRoot("project-wizard/cli")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
@TestMetadata("testData/projectTemplatesBuildFileGeneration")
public class ProjectTemplateBuildFileGenerationTestGenerated extends AbstractProjectTemplateBuildFileGenerationTest {
    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTest, this, testDataFilePath);
    }

    @TestMetadata("composeDesktopApplication")
    public void testComposeDesktopApplication() throws Exception {
        runTest("testData/projectTemplatesBuildFileGeneration/composeDesktopApplication/");
    }

    @TestMetadata("composeMultiplatformApplication")
    public void testComposeMultiplatformApplication() throws Exception {
        runTest("testData/projectTemplatesBuildFileGeneration/composeMultiplatformApplication/");
    }

    @TestMetadata("consoleApplication")
    public void testConsoleApplication() throws Exception {
        runTest("testData/projectTemplatesBuildFileGeneration/consoleApplication/");
    }

    @TestMetadata("frontendApplication")
    public void testFrontendApplication() throws Exception {
        runTest("testData/projectTemplatesBuildFileGeneration/frontendApplication/");
    }

    @TestMetadata("fullStackWebApplication")
    public void testFullStackWebApplication() throws Exception {
        runTest("testData/projectTemplatesBuildFileGeneration/fullStackWebApplication/");
    }

    @TestMetadata("multiplatformLibrary")
    public void testMultiplatformLibrary() throws Exception {
        runTest("testData/projectTemplatesBuildFileGeneration/multiplatformLibrary/");
    }

    @TestMetadata("nativeApplication")
    public void testNativeApplication() throws Exception {
        runTest("testData/projectTemplatesBuildFileGeneration/nativeApplication/");
    }
}
