/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.operation.instrumentation;

import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.operation.OperationRootNode;
import com.oracle.truffle.api.source.SourceSection;

public class InstrumentTreeNode extends Node implements InstrumentableNode {

    private final Class<? extends Tag> tag;
    private final int bci;
    @Children private InstrumentTreeNode[] children;

    public InstrumentTreeNode(Class<? extends Tag> tag, int bci) {
        this.tag = tag;
        this.bci = bci;
        this.children = null;
    }

    public boolean hasTag(Class<? extends Tag> which) {
        return tag == which;
    }

    @Override
    protected void onReplace(Node newNode, CharSequence reason) {
        ((OperationRootNode) getRootNode()).onInstrumentReplace(this, (InstrumentTreeNode) newNode);
    }

    public Class<? extends Tag> getTag() {
        return tag;
    }

    public void setChildren(InstrumentTreeNode[] children) {
        this.children = insert(children);
    }

    @Override
    public SourceSection getSourceSection() {
        return ((OperationRootNode) getRootNode()).getSourceSectionAtBci(bci);
    }

    @Override
    public NodeCost getCost() {
        return NodeCost.NONE;
    }

    public boolean isInstrumentable() {
        return true;
    }

    public WrapperNode createWrapper(ProbeNode probe) {
        System/**/.out.println(" attaching " + probe + " to " + Tag.getIdentifier(tag) + " at " + bci);
        return new Wrapper(this, probe);
    }

    @Override
    public String toString() {
        return String.format("instrument-tree %s @%04x for %s", Tag.getIdentifier(tag), bci, getRootNode());
    }

    static final class Wrapper extends InstrumentTreeNode implements WrapperNode {
        @Child private InstrumentTreeNode delegateNode;
        @Child private ProbeNode probeNode;

        Wrapper(InstrumentTreeNode delegateNode, ProbeNode probeNode) {
            super(delegateNode.tag, delegateNode.bci);
            this.delegateNode = delegateNode;
            this.probeNode = probeNode;
        }

        @Override
        public InstrumentTreeNode getDelegateNode() {
            return delegateNode;
        }

        @Override
        public ProbeNode getProbeNode() {
            return probeNode;
        }
    }
}
