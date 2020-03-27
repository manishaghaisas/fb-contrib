/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2019 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fbcontrib.detect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.BootstrapMethod;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantCP;
import org.apache.bcel.classfile.ConstantInvokeDynamic;
import org.apache.bcel.classfile.ConstantMethodHandle;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.Type;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.FQMethod;
import com.mebigfatguy.fbcontrib.utils.OpcodeUtils;
import com.mebigfatguy.fbcontrib.utils.QMethod;
import com.mebigfatguy.fbcontrib.utils.SignatureBuilder;
import com.mebigfatguy.fbcontrib.utils.SignatureUtils;
import com.mebigfatguy.fbcontrib.utils.StopOpcodeParsingException;
import com.mebigfatguy.fbcontrib.utils.ToString;
import com.mebigfatguy.fbcontrib.utils.Values;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.CustomUserValue;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.ba.ClassContext;

/**
 * looks for issues around use of @FunctionalInterface classes, especially in
 * use with Streams..
 */
@CustomUserValue
public class FunctionalInterfaceIssues extends BytecodeScanningDetector {

    private static final QMethod CONTAINS = new QMethod("contains", SignatureBuilder.SIG_OBJECT_TO_BOOLEAN);
    private static final QMethod SIZE = new QMethod("size", SignatureBuilder.SIG_VOID_TO_INT);

    private static final FQMethod COLLECT = new FQMethod("java/util/stream/Stream", "collect",
            "(Ljava/util/stream/Collector;)Ljava/lang/Object;");
    private static final FQMethod FILTER = new FQMethod("java/util/stream/Stream", "filter",
            "(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;");
    private static final FQMethod FINDFIRST = new FQMethod("java/util/stream/Stream", "findFirst",
            "()Ljava/util/Optional;");
    private static final FQMethod ISPRESENT = new FQMethod("java/util/Optional", "isPresent",
            SignatureBuilder.SIG_VOID_TO_BOOLEAN);
    private static final FQMethod GET = new FQMethod("java/util/List", "get", SignatureBuilder.SIG_INT_TO_OBJECT);

    enum ParseState {
        NORMAL, LAMBDA;
    }

    enum AnonState {
        SEEN_NOTHING, SEEN_ALOAD_0, SEEN_ALOAD_1, SEEN_INVOKE
    }

    enum FIIUserValue {
        COLLECT_ITEM, FILTER_ITEM, FINDFIRST_ITEM;
    }

    private BugReporter bugReporter;
    private JavaClass cls;
    private OpcodeStack stack;
    private BootstrapMethods bootstrapAtt;
    private Map<String, List<FIInfo>> functionalInterfaceInfo;
    private Map<String, BugType> anonymousBugType;

    private ParseState parseState;
    private AnonState anonState;
    private boolean isParmLambda;

    public FunctionalInterfaceIssues(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    @Override
    public void visitClassContext(ClassContext classContext) {
        try {
            cls = classContext.getJavaClass();
            if (cls.getMajor() >= Const.MAJOR_1_8) {
                bootstrapAtt = getBootstrapAttribute(cls);
                if (bootstrapAtt != null) {
                    stack = new OpcodeStack();
                    functionalInterfaceInfo = new HashMap<>();
                    anonymousBugType = new HashMap<>();
                    parseState = ParseState.NORMAL;
                    super.visitClassContext(classContext);
                    parseState = ParseState.LAMBDA;
                    super.visitClassContext(classContext);

                    for (Map.Entry<String, List<FIInfo>> entry : functionalInterfaceInfo.entrySet()) {
                        for (FIInfo fii : entry.getValue()) {
                            bugReporter.reportBug(
                                    new BugInstance(this, anonymousBugType.get(entry.getKey()).name(), NORMAL_PRIORITY)
                                            .addClass(this).addMethod(cls, fii.getMethod())
                                            .addSourceLine(fii.getSrcLine()));
                        }
                    }
                }
            }
        } finally {
            functionalInterfaceInfo = null;
            anonymousBugType = null;
            bootstrapAtt = null;
            stack = null;
            cls = null;
        }
    }

    @Override
    public void visitCode(Code obj) {

        stack.resetForMethodEntry(this);
        Method m = getMethod();
        switch (parseState) {
        case LAMBDA:
            if ((m.getAccessFlags() & Const.ACC_SYNTHETIC) != 0) {
                List<FIInfo> fiis = functionalInterfaceInfo.get(m.getName());
                if (fiis != null) {
                    int numParms = SignatureUtils.getNumParameters(m.getSignature());
                    if (numParms < 1 || numParms > 2) {
                        functionalInterfaceInfo.remove(m.getName());
                    } else {
                        Type t = m.getReturnType();
                        if (!t.getSignature().startsWith("L")) {
                            // javac generates a lambda for your to do unboxing, so don't report
                            functionalInterfaceInfo.remove(m.getName());
                        } else {
                            isParmLambda = numParms == 2;
                        if (isParmLambda) {
                            Iterator<FIInfo> it = fiis.iterator();
                            while (it.hasNext()) {
                                FIInfo fi = it.next();
                                if (!fi.getMethod().isStatic()) {
                                    it.remove();
                                }
                            }

                            if (fiis.isEmpty()) {
                                isParmLambda = false;
                                functionalInterfaceInfo.remove(getMethod().getName());
                                return;
                            }
                            }
                            try {
                                anonState = AnonState.SEEN_NOTHING;
                                super.visitCode(obj);
                            } catch (StopOpcodeParsingException e) {
                            }
                        }
                    }
                }
            }
            break;

        case NORMAL:
            if ((m.getAccessFlags() & Const.ACC_SYNTHETIC) == 0) {
                super.visitCode(obj);
                break;
            }
        }
    }

    @Override
    public void sawOpcode(int seen) {
        FIIUserValue userValue = null;

        try {
            if (parseState == ParseState.LAMBDA) {
                switch (anonState) {
                case SEEN_NOTHING:
                    if (seen == Const.ALOAD_0) {
                        anonState = AnonState.SEEN_ALOAD_0;
                    } else {
                        functionalInterfaceInfo.remove(getMethod().getName());
                        throw new StopOpcodeParsingException();
                    }
                    break;

                case SEEN_ALOAD_0:
                    if ((seen == Const.INVOKEVIRTUAL) || (seen == Const.INVOKEINTERFACE)) {
                        String signature = getSigConstantOperand();
                        if (signature.startsWith("()")) {
                            anonState = AnonState.SEEN_INVOKE;
                        } else {
                            functionalInterfaceInfo.remove(getMethod().getName());
                            throw new StopOpcodeParsingException();
                        }
                    } else if ((seen == Const.ARETURN) && (getPC() == 1)) {
                        List<FIInfo> infos = functionalInterfaceInfo.get(getMethod().getName());
                        if (infos != null) {
                            Iterator<FIInfo> it = infos.iterator();
                            while (it.hasNext()) {
                                FIInfo info = it.next();
                                if (info.wasPrecededByExplicitStackOp()) {
                                    it.remove();
                                }
                            }
                            if (infos.isEmpty()) {
                                functionalInterfaceInfo.remove(getMethod().getName());
                            }
                        }

                        anonymousBugType.put(getMethod().getName(), BugType.FII_USE_FUNCTION_IDENTITY);
                        throw new StopOpcodeParsingException();
                    } else if (seen == Const.ALOAD_1) {
                        if (!isParmLambda) {
                            functionalInterfaceInfo.remove(getMethod().getName());
                            throw new StopOpcodeParsingException();
                        }

                        anonState = AnonState.SEEN_ALOAD_1;
                    } else {
                        functionalInterfaceInfo.remove(getMethod().getName());
                        throw new StopOpcodeParsingException();
                    }
                    break;

                case SEEN_ALOAD_1:
                    if ((seen == Const.INVOKEVIRTUAL) || (seen == Const.INVOKEINTERFACE)) {
                        String clsName = getClassConstantOperand();
                        String methodName = getNameConstantOperand();
                        if (clsName.startsWith("java/lang/")
                                && (methodName.endsWith("Value") || "valueOf".equals(methodName))) {
                            break;
                        }

                        if (SignatureUtils.getNumParameters(getSigConstantOperand()) == 1) {
                            anonState = AnonState.SEEN_INVOKE;
                        } else {
                            functionalInterfaceInfo.remove(getMethod().getName());
                            throw new StopOpcodeParsingException();
                        }
                    } else {
                        functionalInterfaceInfo.remove(getMethod().getName());
                        throw new StopOpcodeParsingException();
                    }
                    break;

                case SEEN_INVOKE:
                    if (!OpcodeUtils.isReturn(seen)) {
                        functionalInterfaceInfo.remove(getMethod().getName());
                    }

                    if (stack.getStackDepth() > 0) {
                        OpcodeStack.Item itm = stack.getStackItem(0);
                        if (!itm.getSignature().equals(SignatureUtils.getReturnSignature(getMethod().getSignature()))) {
                            functionalInterfaceInfo.remove(getMethod().getName());
                        }
                    }
                    anonymousBugType.put(getMethod().getName(), BugType.FII_USE_METHOD_REFERENCE);
                    throw new StopOpcodeParsingException();

                default:
                    functionalInterfaceInfo.remove(getMethod().getName());
                    throw new StopOpcodeParsingException();
                }
            } else {
                switch (seen) {
                case Const.INVOKEDYNAMIC:
                    ConstantInvokeDynamic cid = (ConstantInvokeDynamic) getConstantRefOperand();

                    ConstantMethodHandle cmh = getMethodHandle(cid.getBootstrapMethodAttrIndex());
                    String anonName = getAnonymousName(cmh);
                    if (anonName != null) {

                        List<FIInfo> fiis = functionalInterfaceInfo.get(anonName);
                        if (fiis == null) {
                            fiis = new ArrayList<>();
                            functionalInterfaceInfo.put(anonName, fiis);
                        }

                        int lastOp = getPrevOpcode(1);
                        FIInfo fii = new FIInfo(getMethod(), SourceLineAnnotation.fromVisitedInstruction(this),
                                (lastOp == Const.GETFIELD) || (lastOp == Const.GETSTATIC)
                                        || OpcodeUtils.isALoad(lastOp));
                        fiis.add(fii);
                    }
                    break;

                case Const.INVOKEINTERFACE:
                    QMethod m = new QMethod(getNameConstantOperand(), getSigConstantOperand());

                    if (CONTAINS.equals(m)) {
                        if (stack.getStackDepth() >= 2) {
                            OpcodeStack.Item itm = stack.getStackItem(1);
                            if (itm.getRegisterNumber() < 0 && FIIUserValue.COLLECT_ITEM == itm.getUserValue()) {
                                bugReporter.reportBug(
                                        new BugInstance(this, BugType.FII_AVOID_CONTAINS_ON_COLLECTED_STREAM.name(),
                                                NORMAL_PRIORITY).addClass(this).addMethod(this).addSourceLine(this));
                            }
                        }
                    } else if (SIZE.equals(m)) {
                        if (stack.getStackDepth() >= 1) {
                            OpcodeStack.Item itm = stack.getStackItem(0);
                            if (itm.getRegisterNumber() < 0 && FIIUserValue.COLLECT_ITEM == itm.getUserValue()) {
                                bugReporter.reportBug(
                                        new BugInstance(this, BugType.FII_AVOID_SIZE_ON_COLLECTED_STREAM.name(),
                                                NORMAL_PRIORITY).addClass(this).addMethod(this).addSourceLine(this));
                            }
                        }
                    } else {
                        FQMethod fqm = new FQMethod(getClassConstantOperand(), getNameConstantOperand(),
                                getSigConstantOperand());
                        if (COLLECT.equals(fqm)) {
                            userValue = FIIUserValue.COLLECT_ITEM;
                        } else if (FILTER.equals(fqm)) {
                            if (stack.getStackDepth() > 1) {
                                OpcodeStack.Item itm = stack.getStackItem(1);
                                if (itm.getUserValue() == FIIUserValue.FILTER_ITEM && itm.getRegisterNumber() < 0) {
                                    bugReporter.reportBug(
                                            new BugInstance(this, BugType.FII_COMBINE_FILTERS.name(), LOW_PRIORITY)
                                                    .addClass(this).addMethod(this).addSourceLine(this));
                                }
                            }
                            userValue = FIIUserValue.FILTER_ITEM;
                        } else if (FINDFIRST.equals(fqm)) {
                            if (stack.getStackDepth() > 0) {
                                OpcodeStack.Item itm = stack.getStackItem(0);
                                if (itm.getUserValue() == FIIUserValue.FILTER_ITEM) {
                                    userValue = FIIUserValue.FINDFIRST_ITEM;
                                }
                            }
                        } else if (GET.equals(fqm)) {
                            if (stack.getStackDepth() >= 2) {
                                OpcodeStack.Item itm = stack.getStackItem(0);
                                if (Values.ZERO.equals(itm.getConstant())) {
                                    itm = stack.getStackItem(1);
                                    if (itm.getUserValue() == FIIUserValue.COLLECT_ITEM
                                            && itm.getRegisterNumber() < 0) {
                                        bugReporter.reportBug(new BugInstance(this, BugType.FII_USE_FIND_FIRST.name(),
                                                NORMAL_PRIORITY).addClass(this).addMethod(this).addSourceLine(this));
                                    }
                                }
                            }
                        }
                    }
                    break;

                case Const.INVOKEVIRTUAL:
                    FQMethod fqm = new FQMethod(getClassConstantOperand(), getNameConstantOperand(),
                            getSigConstantOperand());
                    if (ISPRESENT.equals(fqm)) {
                        if (stack.getStackDepth() > 0) {
                            OpcodeStack.Item itm = stack.getStackItem(0);
                            if (itm.getUserValue() == FIIUserValue.FINDFIRST_ITEM && itm.getRegisterNumber() < 0) {
                                bugReporter
                                        .reportBug(new BugInstance(this, BugType.FII_USE_ANY_MATCH.name(), LOW_PRIORITY)
                                                .addClass(this).addMethod(this).addSourceLine(this));
                            }
                        }
                    }
                    break;
                }
            }
        } finally {
            stack.sawOpcode(this, seen);
            if (userValue != null && stack.getStackDepth() > 0) {
                OpcodeStack.Item itm = stack.getStackItem(0);
                itm.setUserValue(userValue);
            }
        }
    }

    @Nullable
    private BootstrapMethods getBootstrapAttribute(JavaClass clz) {
        for (Attribute att : clz.getAttributes()) {
            if (att instanceof BootstrapMethods) {
                return (BootstrapMethods) att;
            }
        }

        return null;
    }

    @Nullable
    private ConstantMethodHandle getMethodHandle(int bootstrapIndex) {
        BootstrapMethod bsMethod = bootstrapAtt.getBootstrapMethods()[bootstrapIndex];

        for (int arg : bsMethod.getBootstrapArguments()) {
            Constant c = getConstantPool().getConstant(arg);
            if (c instanceof ConstantMethodHandle) {
                return (ConstantMethodHandle) c;
            }
        }

        return null;
    }

    @Nullable
    private String getAnonymousName(ConstantMethodHandle cmh) {
        if (cmh == null || cmh.getReferenceKind() != Const.REF_invokeStatic) {
            return null;
        }

        ConstantPool cp = getConstantPool();
        ConstantCP methodRef = (ConstantCP) cp.getConstant(cmh.getReferenceIndex());
        String clsName = methodRef.getClass(cp);
        if (!clsName.equals(cls.getClassName())) {
            return null;
        }

        ConstantNameAndType nameAndType = (ConstantNameAndType) cp.getConstant(methodRef.getNameAndTypeIndex());

        String signature = nameAndType.getSignature(cp);
        int numParms = SignatureUtils.getNumParameters(signature);

        if (numParms == 1 && signature.endsWith("V") || numParms == 2 && !signature.endsWith("V")) {
            return null;
        }

        String methodName = nameAndType.getName(cp);
        if (!isSynthetic(methodName, nameAndType.getSignature(cp))) {
            return null;
        }

        return methodName;
    }

    private boolean isSynthetic(String methodName, String methodSig) {
        for (Method m : cls.getMethods()) {
            if (methodName.equals(m.getName()) && methodSig.equals(m.getSignature())) {
                return m.isSynthetic();
            }
        }

        return false;
    }

    static class FIInfo {
        private Method method;
        private SourceLineAnnotation srcLine;
        private boolean precededByExplicitStackOp;

        public FIInfo(Method method, SourceLineAnnotation srcLine, boolean precededByExplicitStackOp) {
            this.method = method;
            this.srcLine = srcLine;
            this.precededByExplicitStackOp = precededByExplicitStackOp;
        }

        public Method getMethod() {
            return method;
        }

        public SourceLineAnnotation getSrcLine() {
            return srcLine;
        }

        public boolean wasPrecededByExplicitStackOp() {
            return precededByExplicitStackOp;
        }

        @Override
        public String toString() {
            return ToString.build(this);
        }

    }
}
