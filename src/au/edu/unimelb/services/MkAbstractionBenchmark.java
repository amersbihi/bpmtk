package au.edu.unimelb.services;

import au.edu.unimelb.processmining.accuracy.abstraction.mkAutomaton.MarkovianAutomatonAbstraction;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree;
import org.processmining.plugins.InductiveMiner.efficienttree.ProcessTree2EfficientTree;
import org.processmining.processtree.ProcessTree;

import static au.edu.unimelb.services.MkAbstractionTest.loadProcessTree;

import java.io.File;
import java.util.Locale;

public class MkAbstractionBenchmark {

    private static final int K = 2;


    public static void main(String[] args) throws Exception {
        File rootDir = new File(args[0]);

        // Print CSV header
        System.out.println("Grade,NumActivities,SeqGrade,ExclusiveGrade,ParallelGrade,LoopGrade,AverageTime(ms)");

        for (File configFolder : rootDir.listFiles(File::isDirectory)) {
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
            }

            double avgTime = totalTime / (double) ptmlFiles.length;

            // Extract grades from folder name
            // Assuming folder name is like: "10 0.25 0.25 0.25 0.25"
            String[] parts = configFolder.getName().split(" ");
            String numActivities = parts[0];
            String seqGrade = parts[1];
            String exclGrade = parts[2];
            String parGrade = parts[3];
            String loopGrade = parts[4];


            System.out.printf(Locale.US, "%s,%s,%s,%s,%s,%s,%.2f%n", K, numActivities, seqGrade, exclGrade, parGrade, loopGrade, avgTime);

        }
    }
}
