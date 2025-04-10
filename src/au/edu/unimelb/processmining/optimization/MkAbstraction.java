/*
package au.edu.unimelb.processmining.optimization;

import dk.brics.automaton.Automaton;
import dk.brics.automaton.BasicAutomata;
import dk.brics.automaton.BasicOperations;
import org.processmining.processtree.ProcessTree;
import javax.

public class MkAbstraction {

    public static Automaton computeMk(ProcessTree pt, int k) {
        if (pt.isLeaf()) {
            String label = pt.getName();
            if (label == null || label.isEmpty()) {
                return BasicAutomata.makeEmptyString(); // ε
            } else {
                return BasicAutomata.makeString(label);
            }
        } else if (pt.getOperator().equals(ProcessTree.Operator.XOR)) {
            Automaton result = null;
            for (ProcessTree child : pt.getChildren()) {
                Automaton childAuto = computeMk(child, k);
                result = (result == null) ? childAuto : BasicOperations.union(result, childAuto);
            }
            result.determinize();
            return result;
        } else if (pt.getOperator().equals(ProcessTree.Operator.SEQ)) {
            Automaton result = BasicAutomata.makeEmptyString(); // identity for concatenation
            for (ProcessTree child : pt.getChildren()) {
                Automaton childAuto = computeMk(child, k);
                result = BasicOperations.concatenate(result, childAuto);
            }
            result.determinize();
            return result;
        } else {
            throw new UnsupportedOperationException("Only LEAF, XOR, and SEQ are supported so far.");
        }
    }
}*/
