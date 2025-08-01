package au.edu.unimelb.processmining.accuracy.abstraction.mkAutomaton;

import au.edu.qut.processmining.log.SimpleLog;
import dk.brics.automaton.*;
import org.processmining.plugins.InductiveMiner.efficienttree.*;

import java.util.*;

public class MarkovianAutomatonAbstraction {

    public static int artificialMarker = 10_000;

    private Map<Integer, Character> IDsToChar = new HashMap<>();
    private Map<Character, Integer> CharToIDs = new HashMap<>();

    private Automaton automaton;


    public static void main(String[] args) throws Exception {
    }

    // for log-model use we align the ids for the activities in the log with the ones of the model
    public MarkovianAutomatonAbstraction(EfficientTree tree, int k, SimpleLog slog) {
        matchIDsTree(tree, slog);
        this.automaton = computeMk(tree, tree.getRoot(), k);
    }

    // for model-model use we align the second trees ids to the one of the first tree (rest activities have their own unique id)
    public MarkovianAutomatonAbstraction(EfficientTree tree, int k) {
        initializeLabelMapping(tree);                                                   // delete this line for test
        this.automaton = computeMk(tree, tree.getRoot(), k);
    }

    private Automaton computeMk(EfficientTree tree, int node, int k) {
        if (tree.isActivity(node)) {
            return mkLeafNode(IDsToChar.get(tree.getActivity(node)), k);
        }
        if (tree.isTau(node)) {
            return mkLeafNode('τ', k);
        }

        /*if (tree.isActivity(node)) {
            return mkLeafNode(tree.getActivityName(node).toCharArray()[0], k);              // for testing in MkAbstractionTest.class, we dont need a
        }                                                                                   // mapping from log activities to tree activities
        if (tree.isTau(node)) {
            return mkLeafNode('τ', k);
        }*/

        Automaton left = computeMk(tree, tree.getChild(node, 0), k);
        Automaton right = computeMk(tree, tree.getChild(node, 1), k);

        if (tree.isSequence(node)) {
            return mkSequence(left, right, k);
        } else if (tree.isXor(node)) {
            return mkExclusive(left, right);
        } else if (tree.isConcurrent(node)) {
            return mkParallel(left, right, k);
        } else if (tree.isLoop(node)) {
            return mkLoop(left, right, k);
        }

        throw new UnsupportedOperationException("Unknown node type at node " + node);
    }

    private static Automaton mkLeafNode(char label, int k) {
        Automaton a = new Automaton();

        State q0 = new State();
        State q1 = new State();
        State qf = new State();
        qf.setAccept(true);

        // Add transitions
        if (label == 'τ' && k >= 2) {
            // Special case: tau (skip)
            q0.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition('-', qf));
        } else if (k == 2) {
            State q2 = new State();
            q0.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition(label, qf));
            q0.addTransition(new Transition(label, q2));
            q2.addTransition(new Transition('-', qf));
        } else if (k > 2) {
            State q2 = new State();
            q0.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition(label, q2));
            q2.addTransition(new Transition('-', qf));
        }

        a.setInitialState(q0);
        a.setDeterministic(true);
        return a;
    }

    private static Automaton mkExclusive(Automaton a, Automaton b) {
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

    private static Automaton mkSequence(Automaton a, Automaton b, int k) {
        a.expandSingleton();
        b.expandSingleton();

        State q0A = a.getInitialState();
        State q0B = b.getInitialState();
        State qbPlus = q0B.step('+');
        State qfA = a.getAcceptStates().iterator().next();

        // Step 1: Create ε-transition from q0A to q0B to unite States spaces and alphabet
        a.addEpsilons(Collections.singletonList(new StatePair(q0A, q0B)));

        // Step 2: Find Qa- states (states reaching qfA with '-')
        Set<State> QaMinus = findQMinusSet(a, qfA);

        // Step 3: Create transitions from Qa- to successors of qbPlus
        if (qbPlus != null) {
            for (State qMinus : QaMinus) {
                // 1. Add transitions from qbPlus to qMinus
                for (Transition t : qbPlus.getTransitions()) {
                    qMinus.addTransition(new Transition(t.getMin(), t.getMax(), t.getDest()));
                }
                // 2. Remove '-' transitions from qMinus to qfA
                qMinus.getTransitions().removeIf(t -> t.getDest().equals(qfA) && t.getMin() == '-');
            }
        }

        // Step 4: Remove '+' transitions from q0B to qBPlus
        q0A.getTransitions().removeIf(t -> t.getDest().equals(qbPlus) && t.getMin() == '+');

        // Step 5: Create transitions from q0A to successors of q0B
        redirectInitialTransitions(q0A, q0B, qbPlus);

        // Step 6: Clear transitions from q0B and clean up
        q0B.getTransitions().clear();
        a.removeDeadTransitions();

        return computeMkAbstraction(a, k);
    }

    private static Automaton mkLoop(Automaton a, Automaton b, int k) {
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

        return computeMkAbstraction(a, k);
    }

    private static Automaton mkParallel(Automaton a, Automaton b, int k) {
        a.expandSingleton();
        b.expandSingleton();

        // Step 1: compute substring Automatons
        a = computeAllSubstringsAutomaton(a);
        b = computeAllSubstringsAutomaton(b);

        State q0A = a.getInitialState();
        State q0B = b.getInitialState();

        // Step 2: Create Mk automaton from joint alphabet
        Set<Character> alphabet = new HashSet<>();
        alphabet.addAll(getAlphabet(a));
        alphabet.addAll(getAlphabet(b));

        Automaton mk = createMKAutomaton(alphabet, k);
        State q0MK = mk.getInitialState();

        class Triple<A, B, C> {
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
                return Objects.equals(first, triple.first) && Objects.equals(second, triple.second) && Objects.equals(third, triple.third);
            }
        }

        // Step 3: Initialize result automaton and state tracking
        Automaton result = new Automaton();
        Map<Triple<State, State, State>, State> stateMap = new HashMap<>();
        Queue<Triple<State, State, State>> workList = new LinkedList<>();

        Triple<State, State, State> initialTriple = new Triple<>(q0A, q0MK, q0B);
        State initialState = new State();
        result.setInitialState(initialState);

        stateMap.put(initialTriple, initialState);
        workList.add(initialTriple);

        // Step 4: Construct product automaton based on triple transitions
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
                if ((letter == '+' || letter == '-') && (stateA.step(letter) == null || stateB.step(letter) == null || stateMK.step(letter) == null)) {
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

        // Step 5: Final cleanup and DFA transformation
        result.removeDeadTransitions();
        result.setDeterministic(false);
        result.determinize();
        result.minimize();

        return result;
    }

    private static Automaton computeMkAbstraction(Automaton automaton, int k) {
        class PES {
            State origState;
            String memory;
            boolean fromInitial;

            PES(State origState, String memory, boolean fromInitial) {
                this.origState = origState;
                this.memory = memory;
                this.fromInitial = fromInitial;
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof PES)) return false;
                PES other = (PES) o;
                return origState.equals(other.origState) &&
                        memory.equals(other.memory) &&
                        fromInitial == other.fromInitial;
            }

            @Override
            public int hashCode() {
                return origState.hashCode() * 31 + memory.hashCode() + (fromInitial ? 1 : 0);
            }
        }

        Automaton mkAutomaton = new Automaton();
        State s0 = new State(); // initial state of the Mk abstraction
        mkAutomaton.setInitialState(s0);

        Map<PES, State> pesToState = new HashMap<>();
        Queue<PES> queue = new LinkedList<>();
        Set<PES> visited = new HashSet<>();

        PES initialPES = new PES(automaton.getInitialState(), "", true);
        pesToState.put(initialPES, s0);
        queue.add(initialPES);
        visited.add(initialPES);

        while (!queue.isEmpty()) {
            PES current = queue.poll();
            State currentOrig = current.origState;
            String currentMemory = current.memory;
            boolean fromInitial = current.fromInitial;
            State combined = pesToState.get(current);

            // Accept if memory has length k, OR if on init path and at original final state
            if (currentMemory.length() == k ||
                    (fromInitial && currentMemory.length() > 0 && currentOrig.isAccept())) {
                combined.setAccept(true);
                continue;
            }

            for (Transition t : currentOrig.getTransitions()) {
                for (char c = t.getMin(); c <= t.getMax(); c++) {
                    State nextOrig = t.getDest();
                    String nextMemory = updateMemory(currentMemory, c, k);

                    // Continue original path
                    PES nextPES = new PES(nextOrig, nextMemory, fromInitial);
                    State nextCombined = pesToState.get(nextPES);
                    if (nextCombined == null) {
                        nextCombined = new State();
                        pesToState.put(nextPES, nextCombined);
                        if (!visited.contains(nextPES)) {
                            queue.add(nextPES);
                            visited.add(nextPES);
                        }
                    }
                    combined.addTransition(new Transition(c, nextCombined));

                    // Restart from s0, not on initial path anymore
                    PES restartPES = new PES(nextOrig, String.valueOf(c), false);
                    State restartState = pesToState.get(restartPES);
                    if (restartState == null) {
                        restartState = new State();
                        pesToState.put(restartPES, restartState);
                        if (!visited.contains(restartPES)) {
                            queue.add(restartPES);
                            visited.add(restartPES);
                        }
                    }
                    s0.addTransition(new Transition(c, restartState));
                }
            }
        }

        mkAutomaton.setDeterministic(false);
        mkAutomaton.determinize();
        mkAutomaton.minimize();
        return mkAutomaton;
    }

// Helper methods

    private static String updateMemory(String memory, char c, int k) {
        String combined = memory + c;
        if (combined.length() <= k) {
            return combined;
        } else {
            return combined.substring(combined.length() - k);
        }
    }

    private static Automaton computeAllSubstringsAutomaton(Automaton automaton) {
        automaton.expandSingleton();

        State subState = new State();
        List<StatePair> epsilonPairs = new ArrayList<>();

        for (State s : automaton.getStates()) {
            epsilonPairs.add(new StatePair(subState, s));
            s.setAccept(true);  // make all states accepting
        }

        automaton.addEpsilons(epsilonPairs);
        automaton.setInitialState(subState);
        automaton.setDeterministic(false);
        automaton.determinize();

        return automaton;
    }

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

    private static State stepIfPossible(State state, char letter) {
        State next = state.step(letter);
        return next != null ? next : state;
    }

    // Redirects transitions from q0A to states reachable from q0B, excluding transitions to qbPlus.
    private static void redirectInitialTransitions(State q0A, State q0B, State qbPlus) {
        for (Transition t : q0B.getTransitions()) {
            if (!t.getDest().equals(qbPlus)) {
                q0A.addTransition(new Transition(t.getMin(), t.getMax(), t.getDest()));
            }
        }
    }

    // Returns the set of states that have a '-' transition to the given accepting state
    private static Set<State> findQMinusSet(Automaton automaton, State acceptingState) {
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

    private static Set<Character> getAlphabet(Automaton automaton) {
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

    private static Automaton createMKAutomaton(Set<Character> alphabet, int k) {
        Automaton mk = new Automaton();
        State q0mk = new State();
        State qf = new State();
        mk.setInitialState(q0mk);
        qf.setAccept(true);

        if (k == 2) {
            State q1 = new State();
            State q2 = new State();
            q0mk.addTransition(new Transition('+', q1));
            for (char c : alphabet) {
                q0mk.addTransition(new Transition(c, q2));
                q1.addTransition(new Transition(c, qf));
                q2.addTransition(new Transition(c, qf));
            }
            q1.addTransition(new Transition('-', qf));
            q2.addTransition(new Transition('-', qf));
        }

        if (k == 3) {
            State q1 = new State();
            State q2 = new State();
            State q3 = new State();
            State q4 = new State();
            q0mk.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition('-', qf));
            q4.addTransition(new Transition('-', qf));
            q3.addTransition(new Transition('-', qf));
            for (char c : alphabet) {
                q0mk.addTransition(new Transition(c, q2));
                q1.addTransition(new Transition(c, q3));
                q2.addTransition(new Transition(c, q4));
                q3.addTransition(new Transition(c, qf));
                q4.addTransition(new Transition(c, qf));
            }
        }

        if (k == 4) {
            State q1 = new State();
            State q2 = new State();
            State q3 = new State();
            State q4 = new State();
            State q5 = new State();
            State q6 = new State();

            q0mk.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition('-', qf));
            q3.addTransition(new Transition('-', qf));
            q5.addTransition(new Transition('-', qf));
            q6.addTransition(new Transition('-', qf));
            for (char c : alphabet) {
                // From initial
                q0mk.addTransition(new Transition(c, q2));
                // + path
                q1.addTransition(new Transition(c, q3));
                q3.addTransition(new Transition(c, q5));
                q5.addTransition(new Transition(c, qf));
                // - path
                q2.addTransition(new Transition(c, q4));
                q4.addTransition(new Transition(c, q6));
                q6.addTransition(new Transition(c, qf));
            }
        }

        if (k == 5) {
            State q1 = new State();
            State q2 = new State();
            State q3 = new State();
            State q4 = new State();
            State q5 = new State();
            State q6 = new State();
            State q7 = new State();
            State q8 = new State();

            q0mk.addTransition(new Transition('+', q1));
            q1.addTransition(new Transition('-', qf));
            q3.addTransition(new Transition('-', qf));
            q5.addTransition(new Transition('-', qf));
            q7.addTransition(new Transition('-', qf));
            q8.addTransition(new Transition('-', qf));
            for (char c : alphabet) {
                // From initial
                q0mk.addTransition(new Transition(c, q2));
                // + path
                q1.addTransition(new Transition(c, q3));
                q3.addTransition(new Transition(c, q5));
                q5.addTransition(new Transition(c, q7));
                q7.addTransition(new Transition(c, qf));
                // - path
                q2.addTransition(new Transition(c, q4));
                q4.addTransition(new Transition(c, q6));
                q6.addTransition(new Transition(c, q8));
                q8.addTransition(new Transition(c, qf));
            }
        }

        return mk;
    }

    // used for testing with trees with no labels, just chars
    public void initializeLabelMapping(EfficientTree tree) {
        IDsToChar.clear();
        CharToIDs.clear();
        char nextChar = 'a';


        for (int i = 0; i < tree.getInt2activity().length; i++) {
            String activity = tree.getInt2activity()[i];
            if (activity != null && !IDsToChar.containsKey(i)) {
                char c = nextChar++;
                IDsToChar.put(i, c);
                CharToIDs.put(c, i);
            }
        }
    }

    // for use with actual logs with activities
    public void matchIDsTree(EfficientTree tree, SimpleLog log) {
        // Clear previous mappings
        CharToIDs.clear();
        IDsToChar.clear();
        char nextChar = 'a';

        Map<String, Integer> logEIDs = log.getReverseMap();

        Integer lid;
        String label;

        for (int tid = 0; tid < tree.getInt2activity().length; tid++) {
            label = tree.getInt2activity()[tid];

            if ((lid = logEIDs.get(label)) != null && !CharToIDs.containsKey(nextChar)) {
                CharToIDs.put(nextChar, lid);
                IDsToChar.put(tree.getActivity2int().get(label), nextChar);
                nextChar++;
            }
        }
        // Add artificial start/end marker (-2)
        if (!CharToIDs.containsKey('-')) {
            CharToIDs.put('-', artificialMarker);
            IDsToChar.put(artificialMarker, '-');
        }
    }

    public Map<String, Integer> getAcceptedStrings() {
        Map<String, Integer> traceCounts = new HashMap<>();
        Set<State> visitedStates = new HashSet<>();
        explore(automaton.getInitialState(), new StringBuilder(), traceCounts, visitedStates);
        return traceCounts;
    }

    private void explore(State state, StringBuilder current, Map<String, Integer> traceCounts, Set<State> visitedStates) {
        if (visitedStates.contains(state)) return;
        visitedStates.add(state);

        if (state.isAccept()) {
            String trace = current.toString();
            traceCounts.put(trace, traceCounts.getOrDefault(trace, 0) + 1);
        }

        for (Transition t : state.getTransitions()) {
            for (int c = t.getMin(); c <= t.getMax(); c++) {
                current.append((char) c);
                explore(t.getDest(), current, traceCounts, visitedStates);
                current.deleteCharAt(current.length() - 1);
            }
        }

        visitedStates.remove(state);
    }


    public Map<Character, Integer> getCharToIDs() {
        return CharToIDs;
    }

    public Automaton getAutomaton() {
        return automaton;
    }
}