package au.edu.unimelb.services;

import au.edu.unimelb.processmining.accuracy.abstraction.mkAutomaton.MarkovianAutomatonAbstraction;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree;
import org.processmining.plugins.InductiveMiner.efficienttree.ProcessTree2EfficientTree;
import org.processmining.processtree.ProcessTree;
import static au.edu.unimelb.services.MkAbstractionTest.loadProcessTree;

import java.io.File;

public class MkAbstractionBenchmark {

    private static final int K = 5;


    public static void main(String[] args) throws Exception {
        File rootDir = new File(args[0]);

        for (File configFolder : rootDir.listFiles(File::isDirectory)) {
            System.out.println(">> Processing folder: " + configFolder.getName());

            File[] ptmlFiles = configFolder.listFiles((dir, name) -> name.endsWith(".ptml"));
            if (ptmlFiles == null || ptmlFiles.length == 0) continue;

            long totalTime = 0;

            for (File ptmlFile : ptmlFiles) {
                long eStart = System.currentTimeMillis();
                ProcessTree tree = loadProcessTree(ptmlFile);
                EfficientTree effTree = ProcessTree2EfficientTree.convert(tree);
                MarkovianAutomatonAbstraction.computeMk(effTree, effTree.getRoot(), K);
                long eTime = System.currentTimeMillis() - eStart;

                totalTime += eTime;
                // System.out.printf("Tree: %s | eTime: %dms%n", ptmlFile.getName(), eTime);
            }

            double avgTime = totalTime / (double) ptmlFiles.length;
            System.out.printf(">> Folder: %s | Average Time: %.2fms over %d trees%n",
                    configFolder.getName(), avgTime, ptmlFiles.length);
        }
    }
}
