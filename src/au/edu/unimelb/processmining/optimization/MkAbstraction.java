package au.edu.unimelb.processmining.optimization;

import dk.brics.automaton.*;
import org.processmining.processtree.Node;
import org.processmining.processtree.ProcessTree;

import java.util.*;

import static dk.brics.automaton.SpecialOperations.*;


public class MkAbstraction {
    public static void main(String[] args) {
        // Automaton b = mkLeafNode('b');
        // Automaton c = mkLeafNode('c');
        // test the Leafs
        //printLanguage(b, 5);
        //printLanguage(c, 5);

        // test different operators (mkLoop, mkSequence, mkExclusive, mkParallel)
        // Automaton sequence = mkSequence(mkLeafNode('b'), mkLeafNode('c'));
        // Automaton exclusive = mkExclusive(mkLeafNode('a'), mkLeafNode('τ'));
        Automaton loop = mkLoop(mkSequence(mkLeafNode('b'), mkLeafNode('c')), mkExclusive(mkLeafNode('a'), mkLeafNode('τ')));
        // Automaton loop = mkExclusive(mkLeafNode('a'), mkLeafNode('τ'));
        // expand and determinize
        loop.expandSingleton();

        // Output accepted language up to length 5
        System.out.println("Accepted language:");
        printLanguage(loop, 5);
    }

    public static void printLanguage(Automaton automaton, int maxLength) {
        for (int length = 1; length <= maxLength; length++) {
            Set<String> accepted = getStrings(automaton, length);
            if (!accepted.isEmpty()) {
                System.out.println("Strings of length " + length + ":");
                for (String s : accepted) {
                    System.out.println("  " + s);
                }
            }
        }
    }

    public static Object computeMk(ProcessTree pt) {
        if (pt.getType(pt.getRoot()).equals(ProcessTree.Type.MANTASK)) {
            char label = pt.getName().charAt(0);
            if (label == 'τ') {
                return mkLeafNode('τ');
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

    public static Automaton mkLeafNode(char label) {
        Automaton a = new Automaton();

        State q0 = new State();
        State q1 = new State();
        State q2 = new State();
        State qf = new State();
        qf.setAccept(true);

        // Add transitions
        if (label == ('τ')) {
            // Special case: tau (skip)
            q0.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition('-', qf));
        } else {
            // Normal labeled action
            q0.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition(label, qf));
            q0.addTransition(new Transition(label, q2));
            q2.addTransition(new Transition('-', qf));
        }

        a.setInitialState(q0);
        a.setDeterministic(true);
        return a;
    }

    public static Automaton mkExclusive(Automaton a, Automaton b) {
        a.expandSingleton();
        b.expandSingleton();

        // unite both Automatons (libs union function is sufficent)
        Automaton result = BasicOperations.union(a, b);

        result.removeDeadTransitions();
        result.setDeterministic(false);
        result.determinize();
        result.minimize();

        return result;
    }

    /*public static Automaton mkParallel(Automaton a, Automaton b) {

    }*/

    public static Automaton mkLoop(Automaton a, Automaton b) {
        a.expandSingleton();
        b.expandSingleton();

        State q0A = a.getInitialState();
        State q0B = b.getInitialState();
        State qAPlus = q0A.step('+');
        State qBPlus = q0B.step('+');
        State qfA = a.getAcceptStates().iterator().next();
        State qfB = b.getAcceptStates().iterator().next();

        // Step 1: Create ε-transition from q0A to q0B
        a.addEpsilons(Collections.singletonList(new StatePair(q0A, q0B)));

        // Step 2: Find Qa- and Qb- sets
        Set<State> QaMinus = findQMinusSet(a, qfA);
        Set<State> QbMinus = findQMinusSet(a, qfB);

        // Step 3: Build all ε-transitions together
        List<StatePair> epsilonPairs = new ArrayList<>();

        for (State s : QaMinus) {
            epsilonPairs.add(new StatePair(s, qBPlus));
        }
        for (State s : QbMinus) {
            epsilonPairs.add(new StatePair(s, qAPlus));

            // Remove '-' transitions to qfB
            s.getTransitions().removeIf(t -> t.getDest().equals(qfB) && t.getMin() == '-');
        }

        a.addEpsilons(epsilonPairs);

        // Step 4: Remove '+' transitions from q0A to qBPlus
        q0A.getTransitions().removeIf(t -> t.getDest().equals(qBPlus) && t.getMin() == '+');

        // Step 5: Redirect initial transitions from q0A to q0B successors (except qBPlus)
        redirectInitialTransitions(q0A, q0B, qBPlus);

        // Step 6: Clear transitions from q0B and clean up
        q0B.getTransitions().clear();
        a.removeDeadTransitions();
        a.setDeterministic(false);
        a.determinize();
        a.minimize();

        return a;
    }

    public static Automaton mkSequence(Automaton a, Automaton b) {
        a.expandSingleton();
        b.expandSingleton();

        State q0A = a.getInitialState();
        State q0B = b.getInitialState();
        State qbPlus = q0B.step('+');
        State qfA = a.getAcceptStates().iterator().next();

        // Step 1: Create ε-transition from q0A to q0B
        a.addEpsilons(Collections.singletonList(new StatePair(q0A, q0B)));

        // Step 2: Find Qa- states (states reaching qfA with '-')
        Set<State> QaMinus = findQMinusSet(a, qfA);

        // Step 3: Create transitions from Qa- to successors of qbPlus
        if (qbPlus != null) {
            for (State qMinus : QaMinus) {
                // 1. Add transitions from qbPlus to qMinus
                for (Transition t : qbPlus.getTransitions()) {
                    qMinus.addTransition(new Transition(t.getMin(), t.getDest()));
                }
                // 2. Remove '-' transitions from qMinus to qfA
                qMinus.getTransitions().removeIf(t -> t.getDest().equals(qfA) && t.getMin() == '-');
            }
        }

        // Step 4: Remove '+' transitions from q0A to qBPlus
        q0A.getTransitions().removeIf(t -> t.getDest().equals(qbPlus) && t.getMin() == '+');

        // Step 5: Create transitions from q0A to successors of q0B
        redirectInitialTransitions(q0A, q0B, qbPlus);

        // Step 6: Clear transitions from q0B and clean up
        q0B.getTransitions().clear();
        a.removeDeadTransitions();
        a.setDeterministic(false);
        a.determinize();
        a.minimize();

        return a;
    }

    // Redirects transitions from q0A to states reachable from q0B, excluding transitions to qbPlus.
    public static void redirectInitialTransitions(State q0A, State q0B, State qbPlus) {
        for (Transition t : q0B.getTransitions()) {
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
}

    /*public static class UnionResult {
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
*/