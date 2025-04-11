/*
package au.edu.unimelb.processmining.optimization;

import dk.brics.automaton.*;
import org.processmining.processtree.*;


public class MkAbstraction {

    public static Automaton computeMk(ProcessTree pt, Node node) {
        if (node.isLeaf()) {
            String label = pt.getName();
            if (label == null || label.isEmpty()) {
                return BasicAutomata.makeEmptyString();
            } else {
                return BasicAutomata.makeString(label);
            }
        } else if (node.getType().equals(ProcessTree.Type.XOR)) {
            Automaton result = null;
            for (ProcessTree child : pt.getChildren()) {
                Automaton childAuto = computeMk(child);
                result = (result == null) ? childAuto : BasicOperations.union(result, childAuto);
            }
            result.determinize();
            return result;
        } else if (pt.getType().equals(ProcessTree.Type.SEQ)) {
            Automaton result = BasicAutomata.makeEmptyString();
            for (ProcessTree child : pt.getChildren()) {
                Automaton childAuto = computeMk(child);
                result = BasicOperations.concatenate(result, childAuto);
            }
            result.determinize();
            return result;
        } else {
            throw new UnsupportedOperationException("Only LEAF, XOR, and SEQ are supported so far.");
        }
    }
}
*/
