package au.edu.unimelb.services;

import au.edu.qut.processmining.log.SimpleLog;
import au.edu.unimelb.processmining.accuracy.abstraction.mkAutomaton.MarkovianAutomatonAbstraction;
import au.edu.unimelb.processmining.accuracy.abstraction.subtrace.Subtrace;
import au.edu.unimelb.processmining.accuracy.abstraction.subtrace.SubtraceAbstraction;
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

public class MkAbstractionTest {

    private static final int K = 3;
    private static Map<String, Integer> label2id = new HashMap<>();
    private static Map<Integer, String> id2label = new HashMap<>();


    public static void main(String[] args) throws Exception {
        File input = new File(args[0]);
        File[] filesToProcess;

        if (input.isDirectory()) {
            filesToProcess = input.listFiles((dir, name) -> name.endsWith(".ptml"));
        } else if (input.isFile() && input.getName().endsWith(".ptml")) {
            filesToProcess = new File[]{input};
        } else {
            System.err.println("Invalid input. Please provide a valid PTML file or directory.");
            return;
        }

        for (File ptmlFile : filesToProcess) {
            System.out.println("Processing: " + ptmlFile.getName());
            try {
                // Compute former abstraction
                SubtraceAbstraction reference = getReferenceAbstraction(ptmlFile);

                // Load EfficientTree
                EfficientTree tree = ProcessTree2EfficientTree.convert(loadProcessTree(ptmlFile));

                // Compute new Mk-abstraction
                Automaton mkAutomaton = MarkovianAutomatonAbstraction.computeMk(tree, tree.getRoot(), K);
                SubtraceAbstraction myAbstraction = mkAutomatonToSubtraceAbstraction(mkAutomaton.getFiniteStrings(), K);

                // Compare the abstractions
                List<String> onlyinMy = myAbstraction.computeDifferences(reference);
                List<String> onlyinRef = reference.computeDifferences(myAbstraction);
                if (onlyinMy.isEmpty() && onlyinRef.isEmpty()) {
                    System.out.println("Abstractions Match");
                } else {
                    System.out.println("Differences: " + convertIDsToLabels(onlyinMy) + convertIDsToLabels(onlyinRef));
                }
            } catch (Exception e) {
                System.err.println("Error processing file " + ptmlFile.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static SubtraceAbstraction mkAutomatonToSubtraceAbstraction(Set<String> acceptedStrings, int order) {
        SubtraceAbstraction abstraction = new SubtraceAbstraction(order);

        Set<String> prefixes = new HashSet<>();
        Set<String> suffixes = new HashSet<>();
        Set<String> mkTraces = new HashSet<>();

        for (String s : acceptedStrings) {
            if (s.equals("+-")) {
                Subtrace empty = new Subtrace(order);
                empty.add(Subtrace.INIT);
                abstraction.addSubtrace(empty, 1);
            } else if (s.startsWith("+") && s.endsWith("-")) {
                prefixes.add(s.substring(1, s.length() - 1));
                suffixes.add(s.substring(1, s.length() - 1));
            } else if (s.startsWith("+")) {
                prefixes.add(s.substring(1));
            } else if (s.endsWith("-")) {
                suffixes.add(s.substring(0, s.length() - 1));
            } else {
                mkTraces.add(s);
            }
        }

        // compute intersection
        for (String prefix : prefixes) {
            if (suffixes.contains(prefix)) {
                mkTraces.add(prefix);
            }
        }

        for (String trace : mkTraces) {
            Subtrace subtrace = new Subtrace(order);

            for (char c : trace.toCharArray()) {
                String matchedLabel = label2id.keySet().stream()
                        .filter(label -> label.charAt(0) == c)
                        .findFirst()
                        .orElse(null);

                int id = label2id.get(matchedLabel);
                subtrace.add(id);
            }

            if (suffixes.contains(trace)) {
                subtrace.add(Subtrace.INIT);
            }

            abstraction.addSubtrace(subtrace, 1);
        }

        return abstraction;
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
        ProcessTree processTree = loadProcessTree(ptmlFile);
        EfficientTree tree = ProcessTree2EfficientTree.convert(processTree);

        // 2. Convert to AcceptingPetriNet
        PackageManager.Canceller canceller = () -> false;
        EfficientTreeReduce.reduce(tree, new EfficientTreeReduceParametersForPetriNet(false));
        AcceptingPetriNet apn = EfficientTree2AcceptingPetriNet.convert(tree);
        ReduceAcceptingPetriNetKeepLanguage.reduce(apn, canceller);

        // 3. Extract Petri net, initial marking
        Petrinet net = apn.getNet();
        Marking im = apn.getInitialMarking();

        // 6. Call reference subtrace abstraction function
        return SubtraceAbstraction.abstractProcessBehaviour(net, im, K, createDummySimpleLog(net));
    }

    private static List<String> convertIDsToLabels(List<String> referenceStrings) {

        List<String> result = new ArrayList<>();
        for (String s : referenceStrings) {
            if (s.isEmpty()) continue;
            String[] parts = s.split(":");
            StringBuilder sb = new StringBuilder();

            for (String token : parts) {
                if (token.isEmpty()) continue;
                try {
                    int id = Integer.parseInt(token);
                    String label = id2label.get(id);
                    if (label != null && !label.isEmpty()) {
                        sb.append(label.charAt(0));
                    }
                } catch (NumberFormatException e) {
                    // skip invalid token
                }
            }

            result.add(sb.toString());
        }

        return result;
    }

    private static SimpleLog createDummySimpleLog(Petrinet net) {
        Map<String, Integer> traces = new HashMap<>();
        int id = 1;

        for (Transition t : net.getTransitions()) {
            if (!t.isInvisible()) {
                String label = t.getLabel();
                if (!label2id.containsKey(label)) {
                    label2id.put(label, id);
                    id2label.put(id, label);
                    id++;
                }
            }
        }

        XLog dummyXLog = new XLogImpl(null);

        SimpleLog log = new SimpleLog(traces, id2label, dummyXLog);
        log.setReverseMap(label2id);

        return log;
    }
}