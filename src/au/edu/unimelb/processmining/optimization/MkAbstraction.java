package au.edu.unimelb.processmining.optimization;

import dk.brics.automaton.*;
import org.processmining.processtree.Node;
import org.processmining.processtree.ProcessTree;

import java.util.*;


public class MkAbstraction {
    public static void main(String[] args) {
        // Compute for XOR(a, tau)
        //computeXor(computeLeaf("A"), computeTauLeaf());
        MkAbstraction  obj = new MkAbstraction();
        obj.markovianConcatenate(mkLeafNode("A"), mkLeafNode("τ"));
;
    }
    public static Object computeMk(ProcessTree pt, Node node) {
        if (pt.getType(pt.getRoot()).equals(ProcessTree.Type.MANTASK)) {
            String label = pt.getName();
            if (label == null || label.isEmpty()) {
                return mkLeafNode("τ");
            } else {
                return mkLeafNode(label);
            }
        } else if (pt.getType(pt.getRoot()).equals(ProcessTree.Type.XOR)) {
            //return computeXor();
        } else if (pt.getType(pt.getRoot()).equals(ProcessTree.Type.SEQ)) {
            // return markovianConcatenate;
        } else {
            throw new UnsupportedOperationException("Only LEAF, XOR, and SEQ are supported so far.");
        }
        return null;
    }

    public static Automaton mkLeafNode(String label) {
        Automaton a = new Automaton();

        State q0 = new State();
        State q1 = new State();
        State q2 = new State();
        State qf = new State();
        qf.setAccept(true);

        // Add transitions
        if (label.equals("τ")) {
            // Special case: tau (skip)
            q0.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition('-', qf));
        } else {
            // Normal labeled action
            q0.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition(label.charAt(0), qf));
            q0.addTransition(new Transition(label.charAt(0),q2));
            q2.addTransition(new Transition('-', qf));
        }

        a.setInitialState(q0);
        a.setDeterministic(true);
        return a;
    }

    public static Automaton computeExclusive(Automaton a, Automaton b) {
        a.expandSingleton();
        b.expandSingleton();

        // unite both Automatons
        Automaton result = BasicOperations.union(a, b);

        result.removeDeadTransitions();
        result.determinize();

        return result;
    }

    /*public static Automaton parallelComposition(Automaton a, Automaton b) {

    }*/

    public static Automaton markovianLoop(Automaton a, Automaton b) {
        a.expandSingleton();
        b.expandSingleton();

        char EPSILON = 'ε'; // still need to find out how epsilpon-transitions are represented

        State q0A = a.getInitialState();
        State qAPlus = q0A.step('+');
        State qfA = a.getAcceptStates().iterator().next();

        UnionResult result = uniteStateSpaces(a, b);

        Automaton retAutomaton = result.automaton;
        State q0B = result.copiedBInitial;
        State qbPlus = result.copiedBQPlus;
        State qfB = result.copiedBFinal;

        // create transitions from initial state q0A to states reachable by initial state q0B
        redirectInitialTransitions(q0A, q0B, qbPlus);

        // find both Q- sets
        Set<State> QaMinus = findQMinusSet(retAutomaton, qfA);
        Set<State> QbMinus = findQMinusSet(retAutomaton, qfB);

        // create ε-transitions from each QA- state to qb+ and vice versa
        for (State s : QaMinus) {
            s.addTransition(new Transition(EPSILON, qbPlus));
        }

        for (State s : QbMinus) {
            s.addTransition(new Transition(EPSILON, qAPlus));
        }

        q0B.getTransitions().clear();
        retAutomaton.removeDeadTransitions();
        retAutomaton.determinize();
        retAutomaton.setDeterministic(true);

        return retAutomaton;
    }

    public static Automaton markovianConcatenate(Automaton a, Automaton b) {

        //if (a.isSingleton() && b.isSingleton()) {
        a.expandSingleton();
        b.expandSingleton();

        State q0A = a.getInitialState();
        State qfA = a.getAcceptStates().iterator().next();

        // unite Statespace and Transitions
        UnionResult result = uniteStateSpaces(a, b);

        Automaton retAutomaton = result.automaton;
        State q0B = result.copiedBInitial;
        State qbPlus = result.copiedBQPlus;

        // create transitions from initial state q0A to states reachable by initial state q0B
        redirectInitialTransitions(q0A, q0B, qbPlus);

        // Find Qa- states with a '-' transition to qfa by using a reversed map
        Set<State> QaMinus = findQMinusSet(retAutomaton, qfA);

        // create Transactions from States in QA- to states reachable from qb+
        if (qbPlus != null) {
            for (State qMinus : QaMinus) {
                for (Transition t : qbPlus.getTransitions()) {
                    qMinus.addTransition(new Transition(t.getMin(), t.getDest()));
                }
            }
        }

        // set q0b as dead state and remove outgoing transitions
        q0B.getTransitions().clear();
        retAutomaton.removeDeadTransitions();
        retAutomaton.setInitialState(q0A);
        retAutomaton.determinize();
        retAutomaton.setDeterministic(true);

        return a;
    }

    // Redirects transitions from q0B to q0A, excluding transitions to qbPlus.
    public static void redirectInitialTransitions(State q0A, State q0B, State qbPlus) {
        for (Transition t : new ArrayList<>(q0B.getTransitions())) {
            if (!t.getDest().equals(qbPlus)) {
                q0A.addTransition(new Transition(t.getMin(), t.getDest()));
            }
        }
    }

    // Returns the set of states that have a '-' transition to the given accepting state
    public static Set<State> findQMinusSet(Automaton automaton, State acceptingState) {
        Set<State> qMinusStates = new HashSet<>();

        // Build reverse map: destination state -> set of source states
        Map<State, Set<State>> reverseMap = new HashMap<>();
        for (State state : automaton.getStates()) {
            for (Transition t : state.getTransitions()) {
                reverseMap.computeIfAbsent(t.getDest(), k -> new HashSet<>()).add(state);
            }
        }

        // Look at all predecessors of the accepting state
        Set<State> toFinal = reverseMap.getOrDefault(acceptingState, Collections.emptySet());

        for (State s : toFinal) {
            for (Transition t : s.getTransitions()) {
                if (t.getDest().equals(acceptingState) && t.getMin() == '-') {
                    qMinusStates.add(s);
                }
            }
        }

        return qMinusStates;
    }

    public static class UnionResult {
        public final Automaton automaton;
        public final State copiedBInitial;
        public final State copiedBQPlus;
        public final State copiedBFinal;

        public UnionResult(Automaton automaton, State copiedBInitial, State copiedBQPlus, State copiedBFinal) {
            this.automaton = automaton;
            this.copiedBInitial = copiedBInitial;
            this.copiedBQPlus = copiedBQPlus;
            this.copiedBFinal = copiedBFinal;
        }
    }

    public static UnionResult uniteStateSpaces(Automaton a, Automaton b) {
        Map<State, State> stateMapping = new HashMap<>();

        // Clone all states from B
        for (State bState : b.getStates()) {
            State copied = new State();
            copied.setAccept(bState.isAccept());
            stateMapping.put(bState, copied);
        }

        // Copy all transitions from B using the new state references
        for (State bState : b.getStates()) {
            State source = stateMapping.get(bState);
            for (Transition t : bState.getTransitions()) {
                State target = stateMapping.get(t.getDest());
                source.addTransition(new Transition(t.getMin(), target));
            }
        }

        // Add all cloned states into A
        for (State s : stateMapping.values()) {
            a.getStates().add(s);
        }

        return new UnionResult(a,
                stateMapping.get(b.getInitialState()),
                stateMapping.get(b.getInitialState().step('+')),
                stateMapping.get(b.getAcceptStates().iterator().next()));
    }
}
