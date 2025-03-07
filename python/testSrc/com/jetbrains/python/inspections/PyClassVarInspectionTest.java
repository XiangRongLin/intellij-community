// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.inspections;

import com.jetbrains.python.fixtures.PyInspectionTestCase;
import com.jetbrains.python.psi.LanguageLevel;
import org.jetbrains.annotations.NotNull;

public class PyClassVarInspectionTest extends PyInspectionTestCase {

  public void testCanAssignOnClassAttribute() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = 1  # type: ClassVar[int]\n" +
                                            "A.x = 2"));
  }

  public void testCanNotAssignOnInstance() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = 1  # type: ClassVar[int]\n" +
                                            "<warning descr=\"Cannot assign to class variable 'x' via instance\">A().x</warning> = 2"));
  }

  public void testCanNotAssignOutsideOfClassWithTypeComment() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "x = 1  <warning descr=\"'ClassVar' can only be used for assignments in class body\"># type: ClassVar[int]</warning>\n"));
  }

  public void testCanNotAssignOutsideOfClassWithAnnotation() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "x: <warning descr=\"'ClassVar' can only be used for assignments in class body\">ClassVar[int]</warning> = 1\n"));
  }

  public void testCannotAssignOnSubclassInstance() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = 1  # type: ClassVar[int]\n" +
                                            "class B(A):\n" +
                                            "    pass\n" +
                                            "<warning descr=\"Cannot assign to class variable 'x' via instance\">B().x</warning> = 2"));
  }

  public void testCanNotOverrideOnSelf() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = None  # type: ClassVar[int]\n" +
                                            "    def __init__(self) -> None:\n" +
                                            "        <warning descr=\"Cannot assign to class variable 'x' via instance\">self.x</warning> = 1"));
  }

  public void testCanNotOverrideOnSelfInSubclass() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = None  # type: ClassVar[int]\n" +
                                            "class B(A):\n" +
                                            "    def __init__(self) -> None:\n" +
                                            "        <warning descr=\"Cannot assign to class variable 'x' via instance\">self.x</warning> = 0"));
  }

  public void testCanNotAssignOnClassInstanceFromType() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar, Type\n" +
                                            "class A:\n" +
                                            "    x = None  # type: ClassVar[int]\n" +
                                            "def f(a: Type[A]) -> None:\n" +
                                            "    <warning descr=\"Cannot assign to class variable 'x' via instance\">a().x</warning> = 0"));
  }

  public void testCanAssignOnClassObjectFromType() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar, Type\n" +
                                            "class A:\n" +
                                            "    x = None  # type: ClassVar[int]\n" +
                                            "def f(a: Type[A]) -> None:\n" +
                                            "    a.x = 0"));
  }

  public void testCanNotOverrideClassVarWithNormalAttribute() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = 1  # type: ClassVar[int]\n" +
                                            "class B(A):\n" +
                                            "    <warning descr=\"Cannot override class variable 'x' (previously declared on base class 'A') with instance variable\">x</warning> = 2  # type: int"));
  }

  public void testCanNotOverrideNormalAttributeWithClassVar() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = 1  # type: int\n" +
                                            "class B(A):\n" +
                                            "    <warning descr=\"Cannot override instance variable 'x' (previously declared on base class 'A') with class variable\">x</warning> = 2  # type: ClassVar[int]"));
  }

  public void testOverrideClassVarWithImplicitThenExplicitMultiFile() {
    runWithLanguageLevel(LanguageLevel.getLatest(), this::doMultiFileTest);
  }


  public void testCanNotOverrideMultiBaseClassVar() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = 1  # type: ClassVar[int]\n" +
                                            "class B:\n" +
                                            "    x = 2  # type: int\n" +
                                            "class C(A, B):\n" +
                                            "    <warning descr=\"Cannot override instance variable 'x' (previously declared on base class 'B') with class variable\">x</warning> = 3  # type: ClassVar[int]"));
  }

  public void testCanOverrideClassVarWithImplicitClassVar() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = 1  # type: ClassVar[int]\n" +
                                            "class B(A):\n" +
                                            "    x = 2"));
  }

  public void testOverrideClassVarWithImplicitThenExplicit() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class A:\n" +
                                            "    x = 1  # type: ClassVar[int]\n" +
                                            "class B(A):\n" +
                                            "    x = 2\n" +
                                            "class C(B):\n" +
                                            "    x = 3\n" +
                                            "class D(C):\n" +
                                            "    x = 4  # type: ClassVar[int]"));
  }

  public void testClassVarCanNotBeUsedAsFunctionParameterAnnotation() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "\n" +
                                            "def foo(a: <warning descr=\"'ClassVar' cannot be used in annotations for function parameters\">ClassVar</warning>):\n" +
                                            "    pass"));
  }

  public void testClassVarCanNotBeUsedAsFunctionReturnParameter() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "\n" +
                                            "def foo() ->  <warning descr=\"'ClassVar' cannot be used in annotation for a function return value\">ClassVar</warning>:\n" +
                                            "    pass"));
  }

  public void testClassVarCanNotBeDeclaredInFunctionBody() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar\n" +
                                            "class Cls:\n" +
                                            "    def foo(self):\n" +
                                            "        x: <warning descr=\"'ClassVar' cannot be used in annotations for local variables\">ClassVar</warning> = \"str\""));
  }

  // PY-54540
  public void testCanNotUseTypeVarInAnnotation() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar, TypeVar, Generic, List, Set\n" +
                                            "\n" +
                                            "T = TypeVar(\"T\")\n" +
                                            "\n" +
                                            "class A(Generic[T]):\n" +
                                            "    a: ClassVar[<warning descr=\"'ClassVar' parameter cannot include type variables\">T</warning>]\n" +
                                            "    b: ClassVar[List[Set[<warning descr=\"'ClassVar' parameter cannot include type variables\">T</warning>]]]"));
  }

  // PY-54540
  public void testCanNotUseTypeVarInTypeComment() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar, TypeVar, Generic, List, Set\n" +
                                            "\n" +
                                            "T = TypeVar(\"T\")\n" +
                                            "\n" +
                                            "class A(Generic[T]):\n" +
                                            "    a = None <warning descr=\"'ClassVar' parameter cannot include type variables\"># type: ClassVar[T]</warning>\n" +
                                            "    b = None <warning descr=\"'ClassVar' parameter cannot include type variables\"># type: ClassVar[List[Set[T]]]</warning>"));
  }

  // PY-54540
  public void testCanNotUseTypeVarInTuple() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar, TypeVar, Generic, Tuple\n" +
                                            "\n" +
                                            "T = TypeVar(\"T\")\n" +
                                            "\n" +
                                            "class A(Generic[T]):\n" +
                                            "    a: ClassVar[Tuple[int, <warning descr=\"'ClassVar' parameter cannot include type variables\">T</warning>]]\n" +
                                            "    b = None <warning descr=\"'ClassVar' parameter cannot include type variables\"># type: ClassVar[Tuple[int, T]]</warning>"));
  }

  // PY-54540
  public void testTypeVarInComplexType() {
    runWithLanguageLevel(LanguageLevel.getLatest(),
                         () -> doTestByText("from typing import ClassVar, TypeVar, Generic\n" +
                                            "\n" +
                                            "T1 = TypeVar('T1')\n" +
                                            "T2 = TypeVar('T2')\n" +
                                            "T3 = TypeVar('T3')\n" +
                                            "\n" +
                                            "class MyType(Generic[T1, T2]):\n" +
                                            "    pass\n" +
                                            "\n" +
                                            "class A:\n" +
                                            "    a: ClassVar[tuple[MyType[int, <warning descr=\"'ClassVar' parameter cannot include type variables\">T3</warning>], <warning descr=\"'ClassVar' parameter cannot include type variables\">T2</warning>]]\n" +
                                            "    b = None  <warning descr=\"'ClassVar' parameter cannot include type variables\"># type: ClassVar[tuple[MyType[int, T3], T2]]</warning>"));
  }

  @Override
  protected @NotNull Class<? extends PyInspection> getInspectionClass() {
    return PyClassVarInspection.class;
  }
}
