package au.edu.unimelb.services;

import java.io.*;
import java.nio.file.*;
import au.edu.unimelb.processmining.optimization.MkAbstraction;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree;
import org.processmining.plugins.InductiveMiner.efficienttree.ProcessTree2EfficientTree;
import dk.brics.automaton.Automaton;
import org.processmining.processtree.ProcessTree;
import org.processmining.processtree.impl.ProcessTreeImpl;
import org.processmining.processtree.ptml.Ptml;
import org.processmining.processtree.ptml.importing.PtmlImportTree;

public class MkBenchmarkRunner {

    // Set paths
    private static final String PTML_DIR = "C:\\Users\\Amer\\PycharmProjects\\PythonProject\\generated_trees";
    private static final String OUTPUT_CSV = "output/benchmark_results.csv";
    private static final int NUM_RUNS = 5;
    private static final int K = 3; // 2-4

    public static void main(String[] args) throws Exception {
        File dir = new File(PTML_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".ptml"));

        for (File file : files) {
            for (int run = 1; run <= NUM_RUNS; run++) {

                // Run OLD method
                long startOld = System.currentTimeMillis();
                formerMethod(file);
                long endOld = System.currentTimeMillis();

                // Run NEW method
                long startNew = System.currentTimeMillis();
                Automaton newAutomaton = MkAbstraction(file, K);
                long endNew = System.currentTimeMillis();
            }
        }
        System.out.println("Benchmark completed. Results written to " + OUTPUT_CSV);
    }

    // Placeholder: Load .ptml file and perform old abstraction
    private static void formerMethod(File ptmlFile) throws Exception {
        String[] args = new String[]{
                "OPTF", // probably the command
                "C:\\Users\\Amer\\gitprojects\\bpmtk\\src\\au\\edu\\unimelb\\logs",
                "3",    // the 'k' value
                "ILS",  // probably optimization method
                "IM"    // miner type
        };

        ServiceProvider.main(args);
    }

    private static Automaton MkAbstraction(File ptmlFile, int k) throws Exception {

        PtmlImportTree plugin = new PtmlImportTree();
        Ptml ptml = plugin.importPtmlFromStream(null, Files.newInputStream(ptmlFile.toPath()), ptmlFile.getName(), ptmlFile.length());

        ProcessTree processTree = new ProcessTreeImpl();
        ptml.unmarshall(processTree);

        EfficientTree efficientTree = ProcessTree2EfficientTree.convert(processTree);

        return MkAbstraction.computeMk(efficientTree, efficientTree.getRoot(), k);
    }
}
