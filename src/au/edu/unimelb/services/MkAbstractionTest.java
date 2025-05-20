package au.edu.unimelb.services;

import au.edu.qut.processmining.log.SimpleLog;
import au.edu.unimelb.processmining.accuracy.abstraction.subtrace.Subtrace;
import au.edu.unimelb.processmining.accuracy.abstraction.subtrace.SubtraceAbstraction;
import au.edu.unimelb.processmining.optimization.MkAbstraction;
import dk.brics.automaton.Automaton;
import org.deckfour.xes.model.XLog;
import org.deckfour.xes.model.impl.XLogImpl;
import org.processmining.acceptingpetrinet.models.AcceptingPetriNet;
import org.processmining.framework.packages.PackageManager;
import org.processmining.models.graphbased.directed.petrinet.Petrinet;
import org.processmining.models.graphbased.directed.petrinet.elements.Transition;
import org.processmining.models.semantics.petrinet.Marking;
import org.processmining.plugins.InductiveMiner.efficienttree.*;
import org.processmining.plugins.InductiveMiner.reduceacceptingpetrinet.ReduceAcceptingPetriNetKeepLanguage;
import org.processmining.processtree.ProcessTree;
import org.processmining.processtree.impl.ProcessTreeImpl;
import org.processmining.processtree.ptml.Ptml;
import org.processmining.processtree.ptml.importing.PtmlImportTree;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class MkAbstractionTest {

    private static final int K = 4;
    static Map<String, Integer> reverseMap = new HashMap<>();

    public static void main(String[] args) throws Exception {
        String folderPath = "C:\\Users\\Amer\\PycharmProjects\\PythonProject\\generated_trees";

        for (int i = 497; i <= 500; i++) {
            File ptmlFile = new File(folderPath + "\\tree_" + i + ".ptml");

            System.out.println("Processing: " + ptmlFile.getName());

            try {
                // Load EfficientTree
                EfficientTree tree = ProcessTree2EfficientTree.convert(loadProcessTree(ptmlFile));

                // Compute your Mk-abstraction
                Automaton mkAutomaton = MkAbstraction.computeMk(tree, tree.getRoot(), K);
                Set<String> mySubstrings = mkAutomaton.getFiniteStrings();

                // Compute legacy Markov abstraction
                SubtraceAbstraction reference = getReferenceAbstraction(ptmlFile);
                Set<String> refSubstrings = reference.getSubtraces().stream()
                        .map(Subtrace::print)
                        .collect(Collectors.toSet());

                // Convert reference IDs back to labels using the shared reverseMap
                Set<String> converted = convertReferenceIDsToLabels(refSubstrings);

                // Compare the abstractions
                assertEqualSubstrings(mySubstrings, converted);

            } catch (Exception e) {
                System.out.println("Error processing file " + ptmlFile.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static ProcessTree loadProcessTree(File ptmlFile) throws Exception {
        PtmlImportTree plugin = new PtmlImportTree();
        Ptml ptml = plugin.importPtmlFromStream(null, Files.newInputStream(ptmlFile.toPath()), ptmlFile.getName(), ptmlFile.length());
        ProcessTree processTree = new ProcessTreeImpl();
        ptml.unmarshall(processTree);
        return processTree;
    }

    private static SubtraceAbstraction getReferenceAbstraction(File ptmlFile) throws Exception {
        // 1. Load process tree and convert to EfficientTree
        PtmlImportTree plugin = new PtmlImportTree();
        Ptml ptml = plugin.importPtmlFromStream(null, Files.newInputStream(ptmlFile.toPath()), ptmlFile.getName(), ptmlFile.length());
        ProcessTree processTree = new ProcessTreeImpl();
        ptml.unmarshall(processTree);

        EfficientTree tree = ProcessTree2EfficientTree.convert(processTree);

        // 2. Convert to AcceptingPetriNet
        PackageManager.Canceller canceller = () -> false;
        EfficientTreeReduce.reduce(tree, new EfficientTreeReduceParametersForPetriNet(false));
        AcceptingPetriNet apn = EfficientTree2AcceptingPetriNet.convert(tree);
        ReduceAcceptingPetriNetKeepLanguage.reduce(apn, canceller);

        // 3. Extract Petri net, initial marking, and final marking
        Petrinet net = apn.getNet();
        Marking im = apn.getInitialMarking();


        // 6. Call legacy subtrace abstraction function
        return SubtraceAbstraction.abstractProcessBehaviour(net, im, K, createDummySimpleLog(net));
    }

    public static SimpleLog createDummySimpleLog(Petrinet net) {
        Map<String, Integer> traces = new HashMap<>();
        Map<Integer, String> events = new HashMap<>();
        int id = 1;

        for (Transition t : net.getTransitions()) {
            if (!t.isInvisible()) {
                String label = t.getLabel();
                if (!reverseMap.containsKey(label)) {
                    reverseMap.put(label, id);
                    events.put(id, label);
                    id++;
                }
            }
        }

        XLog dummyXLog = new XLogImpl(null);

        SimpleLog log = new SimpleLog(traces, events, dummyXLog);
        log.setReverseMap(reverseMap);

        return log;
    }

    public static Set<String> convertReferenceIDsToLabels(Set<String> referenceStrings) {
        // Invert the reverseMap to get ID → label
        Map<Integer, String> idToLabel = new HashMap<>();
        for (Map.Entry<String, Integer> entry : reverseMap.entrySet()) {
            idToLabel.put(entry.getValue(), entry.getKey());
        }

        Set<String> result = new HashSet<>();
        for (String s : referenceStrings) {
            if (s.isEmpty()) continue;
            String[] parts = s.split(":");
            StringBuilder sb = new StringBuilder();

            for (String token : parts) {
                if (token.isEmpty()) continue;
                try {
                    int id = Integer.parseInt(token);
                    String label = idToLabel.get(id);
                    if (label != null && !label.isEmpty()) {
                        sb.append(label.charAt(0)); // Use first character for label
                    }
                } catch (NumberFormatException e) {
                    // skip invalid token
                }
            }

            result.add(sb.toString());
        }

        return result;
    }


    private static void assertEqualSubstrings(Set<String> a, Set<String> b) {
        Set<String> cleanA = a.stream()
                .map(s -> s.replaceAll("[+-]", "")) // Remove + and - characters
                .collect(Collectors.toSet());

        Set<String> onlyInA = new HashSet<>(cleanA);
        Set<String> onlyInB = new HashSet<>(b);
        onlyInA.removeAll(b);
        onlyInB.removeAll(cleanA);

        if (!onlyInB.isEmpty()) {
            System.out.println("Mismatches found:");
            System.out.println("Only in your abstraction: " + onlyInA);
            System.out.println("Only in reference: " + onlyInB);
        } else {
            System.out.println("Test passed: abstractions match.");
        }
    }
}