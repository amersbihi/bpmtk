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
        State qAPlus = q0A.step('+');
        State qfA = a.getAcceptStates().iterator().next();

        // make epsilon transition to start state of b
        // List<StatePair> p = new ArrayList<>();
        // p.add(new StatePair(a.getInitialState(),b.getInitialState()));
        // a.addEpsilons(p);


        UnionResult result = uniteStateSpaces(a, b);

        Automaton retAutomaton = result.automaton;
        State q0B = result.copiedBInitial;
        State qbPlus = result.copiedBQPlus;
        State qfB = result.copiedBFinal;

        // find both Q- sets
        Set<State> QaMinus = findQMinusSet(retAutomaton, qfA);
        Set<State> QbMinus = findQMinusSet(retAutomaton, qfB);

        // create ε-transitions from each Q- state to q+ state
        List<StatePair> pairs = new ArrayList<>();
        for (State s : QaMinus) {
            pairs.add(new StatePair(s, qbPlus));
        }
        q0B.getTransitions().removeIf(t -> t.getMin() == '+');
        retAutomaton.addEpsilons(pairs);

        for (State s : QbMinus) {
            pairs.add(new StatePair(s, qAPlus));

            List<Transition> toRemove = new ArrayList<>();
            for (Transition t : s.getTransitions()) {
                if (t.getDest().equals(qfB) && t.getMin() == '-') {
                    toRemove.add(t);
                }
            }
            s.getTransitions().removeAll(toRemove);
        }
        retAutomaton.addEpsilons(pairs);

        // create transitions from initial state q0A to states reachable by initial state q0B
        redirectInitialTransitions(q0A, q0B, qbPlus);

        q0B.getTransitions().clear();
        retAutomaton.removeDeadTransitions();
        retAutomaton.setDeterministic(false);
        retAutomaton.determinize();
        retAutomaton.minimize();

        return retAutomaton;
    }

    public static Automaton mkSequence(Automaton a, Automaton b) {

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

        // create Transactions from States in QA- to states reachable from qb+ and remove their connection to qfa
        if (qbPlus != null) {
            for (State qMinus : QaMinus) {
                List<Transition> toRemove = new ArrayList<>();

                // 1. add new transitions from qbPlus
                for (Transition t : qbPlus.getTransitions()) {
                    qMinus.addTransition(new Transition(t.getMin(), t.getDest()));
                }

                // 2. collect invalid '-' transitions to qfA
                for (Transition t : qMinus.getTransitions()) {
                    if (t.getDest().equals(qfA) && t.getMin() == '-') {
                        toRemove.add(t);
                    }
                }

                // 3. remove the transitions to qfa
                qMinus.getTransitions().removeAll(toRemove);
            }
        }

        // set q0b as dead state and remove outgoing transitions
        q0B.getTransitions().clear();
        retAutomaton.removeDeadTransitions();
        retAutomaton.setInitialState(q0A);
        retAutomaton.setDeterministic(false);
        retAutomaton.determinize();
        retAutomaton.minimize();

        return retAutomaton;
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
