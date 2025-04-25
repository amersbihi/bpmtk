package au.edu.unimelb.processmining.optimization;

import dk.brics.automaton.*;
import org.processmining.processtree.*;

import java.util.*;

import static dk.brics.automaton.SpecialOperations.*;


public class MkAbstraction {

    // test different operators (mkLoop, mkSequence, mkExclusive, mkParallel)
    public static void main(String[] args) {
        // Automaton test = mkLeafNode('b');
        // Automaton test = mkLeafNode('c');
        // Automaton test = mkSequence(mkLeafNode('b'), mkLeafNode('c'));
        // Automaton test = mkExclusive(mkLeafNode('a'), mkLeafNode('τ'));
        // Automaton test = mkLoop(mkSequence(mkLeafNode('b'), mkLeafNode('c')), mkExclusive(mkLeafNode('a'), mkLeafNode('τ')))
        Automaton test = mkParallel(mkLoop(mkSequence(mkLeafNode('b'), mkLeafNode('c')), mkExclusive(mkLeafNode('a'), mkLeafNode('τ'))), mkLeafNode('d'));

        // Output accepted language up to length 5
        System.out.println("Accepted language:");
        printLanguage(test, 5);
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

    public static Automaton computeMk(ProcessTree pt) {
        if (pt.getType(pt.getRoot()).equals(ProcessTree.Type.MANTASK)) {
            char label = pt.getName().charAt(0);
            if (label == 'τ') {
                return mkLeafNode('τ');
            } else {
                return mkLeafNode(label);
            }
        } else if (pt.getType(pt.getRoot()).equals(ProcessTree.Type.XOR)) {
            // mkExclusive(computeMk(pt.getRoot().), computeMk(pt.getRoot().));
        } else if (pt.getType(pt.getRoot()).equals(ProcessTree.Type.SEQ)) {
            // return mkSequence(computeMk(pt.getRoot().), computeMk(pt.getRoot().));
        } else if (pt.getType(pt.getRoot()).equals(ProcessTree.Type.LOOPDEF)) {     // not sure whether this represents Loop
            // return mkLoop(computeMk(pt.getRoot().), computeMk(pt.getRoot().));
        } else if (pt.getType(pt.getRoot()).equals(ProcessTree.Type.AND)) {
            // return mkParallel(computeMk(pt.getRoot().), computeMk(pt.getRoot().));
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

    public static Automaton mkParallel(Automaton a, Automaton b) {
        a.expandSingleton();
        b.expandSingleton();

        State q0A = a.getInitialState();
        State q0B = b.getInitialState();
        State qfA = a.getAcceptStates().iterator().next();
        State qfB = b.getAcceptStates().iterator().next();

        // Step 1: Add '-' transitions from initial to final (partial execution support)
        q0A.addTransition(new Transition('-', qfA));
        q0B.addTransition(new Transition('-', qfB));

        // Step 2: Set all states to accepting to allow partial merging
        for (State s : a.getStates()) s.setAccept(true);
        for (State s : b.getStates()) s.setAccept(true);

        // Step 3: Create Mk automaton from joint alphabet
        Set<Character> alphabet = new HashSet<>();
        alphabet.addAll(getAlphabet(a));
        alphabet.addAll(getAlphabet(b));

        Automaton mk = createMKAutomaton(alphabet);
        State q0MK = mk.getInitialState();

        // Step 4: Initialize result automaton and state tracking
        Automaton result = new Automaton();
        Map<Triple<State, State, State>, State> stateMap = new HashMap<>();
        Queue<Triple<State, State, State>> workList = new LinkedList<>();

        Triple<State, State, State> initialTriple = new Triple<>(q0A, q0MK, q0B);
        State initialState = new State();
        result.setInitialState(initialState);

        stateMap.put(initialTriple, initialState);
        workList.add(initialTriple);

        // Step 5: Construct product automaton based on triple transitions
        while (!workList.isEmpty()) {
            Triple<State, State, State> current = workList.poll();
            State stateA = current.first;
            State stateMK = current.second;
            State stateB = current.third;
            State combined = stateMap.get(current);

            // Stop expanding when Mk reaches accepting state — this is a semantic leaf
            if (stateMK.isAccept()) {
                combined.setAccept(true);
                continue;
            }

            for (char letter : getRelevantLetters(stateA, stateB)) {
                // Only allow + or - if all three automata can transition on it
                if ((letter == '+' || letter == '-') &&
                        (stateA.step(letter) == null || stateB.step(letter) == null || stateMK.step(letter) == null)) {
                    continue;
                }

                State nextA = stepIfPossible(stateA, letter);
                State nextMK = stepIfPossible(stateMK, letter);
                State nextB = stepIfPossible(stateB, letter);

                Triple<State, State, State> nextTriple = new Triple<>(nextA, nextMK, nextB);
                State nextCombined = stateMap.get(nextTriple);
                if (nextCombined == null) {
                    nextCombined = new State();
                    stateMap.put(nextTriple, nextCombined);
                    workList.add(nextTriple);
                }

                combined.addTransition(new Transition(letter, nextCombined));
            }
        }

        // Step 6: Final cleanup and DFA transformation
        result.removeDeadTransitions();
        result.setDeterministic(false);
        result.determinize();
        result.minimize();

        return result;
    }

    // Helper methods

    private static Set<Character> getRelevantLetters(State a, State b) {
        Set<Character> letters = new HashSet<>();
        if (a != null) {
            for (Transition t : a.getTransitions()) {
                for (char c = t.getMin(); c <= t.getMax(); c++) letters.add(c);
            }
        }
        if (b != null) {
            for (Transition t : b.getTransitions()) {
                for (char c = t.getMin(); c <= t.getMax(); c++) letters.add(c);
            }
        }
        return letters;
    }

    public static Set<Character> getAlphabet(Automaton automaton) {
        Set<Character> alphabet = new HashSet<>();
        for (State state : automaton.getStates()) {
            for (Transition t : state.getTransitions()) {
                for (char c = t.getMin(); c <= t.getMax(); c++) {
                    if (c != '+' && c != '-') {
                        alphabet.add(c);
                    }
                }
            }
        }
        return alphabet;
    }

    private static State stepIfPossible(State state, char letter) {
        State next = state.step(letter);
        return next != null ? next : state;
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

    private static Automaton createMKAutomaton(Set<Character> alphabet) {
        Automaton mk = new Automaton();
        State q0mk = new State();
        State q1 = new State();
        State q2 = new State();
        State q3 = new State();
        mk.setInitialState(q0mk);
        q3.setAccept(true);

        q0mk.addTransition(new Transition('+', q1));
        for (char c : alphabet) {
            q0mk.addTransition(new Transition(c, q2));
            q1.addTransition(new Transition(c, q3));
            q2.addTransition(new Transition(c, q3));
        }
        q1.addTransition(new Transition('-', q3));
        q2.addTransition(new Transition('-', q3));

        return mk;
    }

    public static class Triple<A, B, C> {
        public final A first;
        public final B second;
        public final C third;

        public Triple(A first, B second, C third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Triple<?, ?, ?> triple = (Triple<?, ?, ?>) o;
            return Objects.equals(first, triple.first) &&
                    Objects.equals(second, triple.second) &&
                    Objects.equals(third, triple.third);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second, third);
        }
    }
}