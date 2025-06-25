package au.edu.unimelb.services;

import au.edu.unimelb.processmining.accuracy.abstraction.mkAutomaton.MarkovianAutomatonAbstraction;
import au.edu.unimelb.processmining.accuracy.abstraction.subtrace.SubtraceAbstraction;
import org.processmining.acceptingpetrinet.models.AcceptingPetriNet;
import org.processmining.framework.packages.PackageManager;
import org.processmining.models.graphbased.directed.petrinet.Petrinet;
import org.processmining.models.semantics.petrinet.Marking;
import org.processmining.plugins.InductiveMiner.efficienttree.*;
import org.processmining.plugins.InductiveMiner.reduceacceptingpetrinet.ReduceAcceptingPetriNetKeepLanguage;
import org.processmining.processtree.ProcessTree;

import static au.edu.unimelb.services.MkAbstractionTest.createDummySimpleLog;
import static au.edu.unimelb.services.MkAbstractionTest.loadProcessTree;

import java.io.File;
import java.util.Locale;
import java.util.Map;

public class MkAbstractionBenchmark {

    private enum Test {OLD, NEW}

    private static final int K = 4;


    public static void main(String[] args) throws Exception {
        File rootDir = new File(args[0]);
        Test code = Test.valueOf(args[1]);

        // Print CSV header
        System.out.println("Grade,NumActivities,SeqGrade,ExclusiveGrade,ParallelGrade,LoopGrade,AverageTime(ms)");

        switch (code) {
            case NEW:
                for (File configFolder : rootDir.listFiles(File::isDirectory)) {
                    File[] ptmlFiles = configFolder.listFiles((dir, name) -> name.endsWith(".ptml"));
                    if (ptmlFiles == null || ptmlFiles.length == 0) continue;

                    long totalTime = 0;

                    for (File ptmlFile : ptmlFiles) {
                        long eStart = System.currentTimeMillis();
                        ProcessTree tree = loadProcessTree(ptmlFile);
                        EfficientTree effTree = ProcessTree2EfficientTree.convert(tree);
                        MarkovianAutomatonAbstraction mk = new MarkovianAutomatonAbstraction(effTree, K);
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
            case OLD:
                for (File configFolder : rootDir.listFiles(File::isDirectory)) {
                    File[] ptmlFiles = configFolder.listFiles((dir, name) -> name.endsWith(".ptml"));
                    if (ptmlFiles == null || ptmlFiles.length == 0) continue;

                    long totalTime = 0;

                    for (File ptmlFile : ptmlFiles) {
                        long eStart = System.currentTimeMillis();
                        ProcessTree processTree = loadProcessTree(ptmlFile);
                        EfficientTree tree = ProcessTree2EfficientTree.convert(processTree);
                        PackageManager.Canceller canceller = () -> false;
                        EfficientTreeReduce.reduce(tree, new EfficientTreeReduceParametersForPetriNet(false));
                        AcceptingPetriNet apn = EfficientTree2AcceptingPetriNet.convert(tree);
                        ReduceAcceptingPetriNetKeepLanguage.reduce(apn, canceller);
                        Petrinet net = apn.getNet();
                        Marking im = apn.getInitialMarking();
                        SubtraceAbstraction.abstractProcessBehaviour(net, im, K, createDummySimpleLog(net));
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
}
