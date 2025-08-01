package au.edu.unimelb.processmining.optimization;

import au.edu.qut.processmining.log.SimpleLog;
import au.edu.unimelb.processmining.accuracy.abstraction.LogAbstraction;
import au.edu.unimelb.processmining.accuracy.abstraction.subtrace.SubtraceAbstraction;
import org.processmining.acceptingpetrinet.models.AcceptingPetriNet;
import org.processmining.models.graphbased.directed.bpmn.BPMNDiagram;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree;

import com.raffaeleconforti.conversion.bpmn.BPMNToPetriNetConverter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;

import static java.lang.Thread.sleep;
import au.edu.unimelb.processmining.accuracy.abstraction.distances.ConfusionMatrix;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree2AcceptingPetriNet;
import org.processmining.plugins.pnml.exporting.PnmlExportNetToPNML;
import org.processmining.plugins.kutoolbox.utils.FakePluginContext;
import org.processmining.models.graphbased.directed.petrinet.Petrinet;
import java.io.File;

public class IteratedLocalSearch implements Metaheuristics {

    private static int PERTURBATION_STRENGTH = 2;

    private MinerProxy minerProxy;

    private EfficientTree currentTree;
    private EfficientTree bestTree;

    private BPMNDiagram currentBPMN;
    private BPMNDiagram bestBPMN;

    private SimpleDirectlyFollowGraph currentSDFG;
    private SimpleDirectlyFollowGraph bestSDFG;

    private ArrayList<Double> bestScores;
    private ArrayList<Double> bestFitness;
    private ArrayList<Double> bestPrecision;
    private SubtraceAbstraction bestStaProcess;

    private ArrayList<Integer> hits;
    Double[] currentAccuracy = new Double[3];

    private SubtraceAbstraction staLog;
    private SubtraceAbstraction staProcess;

    private PrintWriter writer;
    private int perturbations;

    private int noImprovementCounter = 0;
    private int maxIterationsBeforeRaise = 4;

    private int maxK = 5;

    private long MineTime;
    private long ModifyTime;
    private long ComputeTime;

    public IteratedLocalSearch(MinerProxy proxy) {
        minerProxy = proxy;
    }

    public SimpleDirectlyFollowGraph getBestSDFG() {
        return bestSDFG;
    }

    public BPMNDiagram searchOptimalSolution(SimpleLog slog, int order, int maxit, int neighbourhood, int timeout, String modelName) throws IOException {
        int iterations = 0;
        int icounter = 0;
        perturbations = 0;
        staLog = LogAbstraction.subtrace(slog, order);

        ExecutorService multiThreadService;
        MarkovianBasedEvaluator evalThread;
        Future<Object[]> evalResult;
        Map<SimpleDirectlyFollowGraph, Future<Object[]>> neighboursEvaluations = new HashMap<>();
        String subtrace;
        Set<SimpleDirectlyFollowGraph> neighbours = new HashSet<>();
        Object[] result;
        ArrayList<String> differences;

        SimpleDirectlyFollowGraph tmpSDFG;
        BPMNDiagram tmpBPMN;

        boolean improved;
        boolean export = false;

        hits = new ArrayList<>();
        bestScores = new ArrayList<>();
        bestFitness = new ArrayList<>();
        bestPrecision = new ArrayList<>();

        writer = null;
        try {
            String safeName = modelName.replaceAll("[/\\\\.]", "_");
            writer = new PrintWriter("." +
                    "/ils_" + safeName + ".csv");
            writer.println("iteration,fitness,precision,fscore,itime");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR - impossible to print the markovian abstraction.");
        }

        long eTime = System.currentTimeMillis();
        long iTime = System.currentTimeMillis();

        start(slog, order);
        bestFitness.add(currentAccuracy[0]);
        bestPrecision.add(currentAccuracy[1]);
        bestScores.add(currentAccuracy[2]);
        hits.add(iterations);
        bestSDFG = currentSDFG;
        bestBPMN = currentBPMN;

        // System.currentTimeMillis() - eTime < timeout &&
        while ( System.currentTimeMillis() - eTime < timeout && iterations < maxit && currentSDFG != null) {
            try {

                System.out.println("ITERATION: " + iterations);

                if (currentAccuracy[2] > bestScores.get(bestScores.size() - 1)) {
                    System.out.println("INFO - improved fscore " + currentAccuracy[2]);
                    bestFitness.add(currentAccuracy[0]);
                    bestPrecision.add(currentAccuracy[1]);
                    bestScores.add(currentAccuracy[2]);
                    hits.add(iterations);
                    bestSDFG = currentSDFG;
                    bestBPMN = currentBPMN;
                    noImprovementCounter = 0;
                } else {
                    noImprovementCounter++;
                }

                /*if (noImprovementCounter >= maxIterationsBeforeRaise && order < maxK) {
                    differences = staProcess.computeDifferences(staLog);

                    order++;
                    System.out.println("\u001B[32mINFO - No improvement for " + noImprovementCounter + " iterations, increasing k to " + order + "\u001B[0m");

                    // Recompute Log abstraction at new k
                    staLog = LogAbstraction.subtrace(slog, order);

                    // Re-evaluate best tree at new k
                    MarkovianBasedEvaluator reevaluateBest = new MarkovianBasedEvaluator(staLog, differences, slog, minerProxy, bestBPMN, order);
                    ExecutorService reevaluateService = Executors.newSingleThreadExecutor();
                    Future<Object[]> reevaluateResult = reevaluateService.submit(reevaluateBest);
                    Object[] newResult = reevaluateResult.get(timeout, TimeUnit.MILLISECONDS);
                    reevaluateService.shutdownNow();

                    currentAccuracy[0] = (Double) newResult[0];
                    currentAccuracy[1] = (Double) newResult[1];
                    currentAccuracy[2] = (Double) newResult[2];
                    staProcess = (SubtraceAbstraction) newResult[3];
                    currentBPMN = (BPMNDiagram) newResult[4];
                    ComputeTime += (long) newResult[5];
                    currentSDFG = bestSDFG;

                    noImprovementCounter = 0;
                }*/

                iTime = System.currentTimeMillis() - iTime;
                if (export)
                    AutomatedProcessDiscoveryOptimizer.exportBPMN(currentBPMN, ".\\ils_" + modelName + "_" + iterations + ".bpmn");
                writer.println(iterations + "," + currentAccuracy[0] + "," + currentAccuracy[1] + "," + currentAccuracy[2] + "," + iTime);
                writer.flush();
                iterations++;
                iTime = System.currentTimeMillis();

                long mineStart = System.currentTimeMillis();
                if (currentAccuracy[1] > currentAccuracy[0]) {
/**     if precision is higher than fitness, we explore the DFGs having more edges.
 *      to do so, we select the most frequent edges of the markovian abstraction of the log that do not appear
 *      in the markovian abstraction of the process, NOTE: each edge is a subtrace.
 *      we select C*N subtraces, and we add C subtraces at a time to a copy of the current DFG.
 *      each of this copy is considered to be a neighbour of the current DFG with an improved fitness.
 *      for each of this copy we compute the f-score, and we retain the one with highest f-score.
 **/
                    staLog.computeDifferences(staProcess);
                    subtrace = staLog.nextMismatch();
                    tmpSDFG = new SimpleDirectlyFollowGraph(currentSDFG);
                    while (neighbours.size() != neighbourhood && subtrace != null) {
                        if (subtrace.isEmpty() && (subtrace = staLog.nextMismatch()) == null) break;

                        if ((subtrace = tmpSDFG.enhance(subtrace, 1)) == null) subtrace = staLog.nextMismatch();
                        else {
                            neighbours.add(tmpSDFG);
                            tmpSDFG = new SimpleDirectlyFollowGraph(currentSDFG);
                        }
                    }

                } else {
/**     if fitness is higher than precision, we explore the DFGs having less edges.
 *      to do so, we select random edges of the markovian abstraction of the process that do not appear
 *      in the markovian abstraction of the log.
 *      we select C*N subtraces, and we add C subtraces at a time to a copy of the current DFG.
 *      each of this copy is considered to be a neighbour of the current DFG with an improved fitness.
 *      for each of this copy we compute the f-score, and we retain the one with highest f-score.
 **/
                    staProcess.computeDifferences(staLog);
                    subtrace = staProcess.nextMismatch();
                    tmpSDFG = new SimpleDirectlyFollowGraph(currentSDFG);
                    while (neighbours.size() != neighbourhood && subtrace != null) {
                        if (subtrace.isEmpty() && (subtrace = staProcess.nextMismatch()) == null) break;

                        if ((subtrace = tmpSDFG.reduce(subtrace, 1)) == null) subtrace = staProcess.nextMismatch();
                        else {
                            neighbours.add(tmpSDFG);
                            tmpSDFG = new SimpleDirectlyFollowGraph(currentSDFG);
                        }
                    }
                }
                long mineEnd = System.currentTimeMillis();
                MineTime += (mineEnd - mineStart);

//                System.out.println("INFO - selected " + neighbours.size() + " neighbours.");

                if (neighbours.isEmpty()) {
//                    System.out.println("WARNING - empty neighbourhood " + neighbours.size() + " neighbours.");
                    while (!perturb(slog, order)) ;
                    continue;
                }

                multiThreadService = Executors.newFixedThreadPool(neighbours.size());
                for (SimpleDirectlyFollowGraph neighbourSDFG : neighbours) {
                    try {
                        long modifyStart = System.currentTimeMillis();
                        tmpBPMN = minerProxy.getBPMN(neighbourSDFG);
                        long modifyEnd = System.currentTimeMillis();
                        ModifyTime += (modifyEnd - modifyStart);
                    } catch (Exception e) {
                        System.out.println("WARNING - discarded one neighbour.");
                        continue;
                    }

                    evalThread = new MarkovianBasedEvaluator(staLog, slog, minerProxy, tmpBPMN, order);
                    evalResult = multiThreadService.submit(evalThread);

                    neighboursEvaluations.put(neighbourSDFG, evalResult);
                }

                sleep(minerProxy.getTimeout());

                improved = false;
                for (SimpleDirectlyFollowGraph neighbourSDFG : neighboursEvaluations.keySet()) {
                    evalResult = neighboursEvaluations.get(neighbourSDFG);
                    if (evalResult.isDone()) {
                        result = evalResult.get();
                        if ((Double) result[2] >= currentAccuracy[2]) {
                            currentAccuracy[0] = (Double) result[0];
                            currentAccuracy[1] = (Double) result[1];
                            currentAccuracy[2] = (Double) result[2];
                            staProcess = (SubtraceAbstraction) result[3];
                            currentBPMN = (BPMNDiagram) result[4];
                            ComputeTime += (long) result[5];
                            currentSDFG = neighbourSDFG;
                            improved = true;
                            icounter = 0;
                        }
                    } else {
                        evalResult.cancel(true);
                    }
                }

                neighbours.clear();
                neighboursEvaluations.clear();
                multiThreadService.shutdownNow();

/**     once we checked all the neighbours accuracies, we select the one improving the current state or none at all.
 *      if the one improving the current state, also improves the global maximum, we update that.
 */
                if (!improved && ++icounter == order) {
                    icounter = 0;
                    while (!perturb(slog, order)) ;
                }

            } catch (Exception e) {
                System.out.println("ERROR - I got tangled in the threads.");
                e.printStackTrace();
                while (!perturb(slog, order)) ;
            }
        }

        eTime = System.currentTimeMillis() - eTime;
        String hitrow = "";
        String fscorerow = "";
        for (int i = 0; i < hits.size(); i++) {
            hitrow += hits.get(i) + ",";
            fscorerow += bestScores.get(i) + ",";
        }

        writer.println(hitrow + (double) (eTime) / 1000.0);
        writer.println(fscorerow + (double) (eTime) / 1000.0);
        writer.close();

        System.out.println("\u001B[32mTotal Mine Time: " + MineTime + "ms\u001B[0m");
        System.out.println("\u001B[32mTotal Modify Time: " + ModifyTime + "ms\u001B[0m");
        System.out.println("\u001B[32mTotal Compute Time: " + ComputeTime + "ms\u001B[0m");

        System.out.println("\u001B[32mBest Fitness achieved: " + bestFitness.get(bestFitness.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mBest Precision achieved: " + bestPrecision.get(bestPrecision.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mBest F-score achieved: " + bestScores.get(bestScores.size() - 1) + "\u001B[0m");

        System.out.println("\u001B[32mTotal Iterations: " + iterations + "\u001B[0m");
        System.out.println("\u001B[32mFinal k value reached: " + order + "\u001B[0m");

        System.out.println("eTIME - " + (double) (eTime) / 1000.0 + "s");
//        System.out.println("STATS - total perturbations: " + perturbations);

        Object[] petrinetObj = BPMNToPetriNetConverter.convert(bestBPMN);
        Petrinet petrinet = (Petrinet) petrinetObj[0];
        PnmlExportNetToPNML exporter = new PnmlExportNetToPNML();
        exporter.exportPetriNetToPNMLFile(new FakePluginContext(), petrinet, new File("C:\\Users\\Amer\\gitprojects\\bpmtk\\models\\bestModel1.pnml"));

        return bestBPMN;
    }

    public EfficientTree searchOptimalTree(SimpleLog slog, int order, int maxit, int neighbourhood, int timeout, String modelName) {
        int iterations = 0;
        int icounter = 0;
        perturbations = 0;
        staLog = LogAbstraction.subtraceTree(slog, order);

        ExecutorService multiThreadService;
        MarkovianBasedEvaluator evalThread;
        Future<Object[]> evalResult;
        Map<SimpleDirectlyFollowGraph, Future<Object[]>> neighboursEvaluations = new HashMap<>();
        String subtrace;
        Set<SimpleDirectlyFollowGraph> neighbours = new HashSet<>();
        Object[] result;
        ArrayList<String> differences;

        SimpleDirectlyFollowGraph tmpSDFG;
        EfficientTree tmpTree;

        boolean improved;
        boolean export = false;

        hits = new ArrayList<>();
        bestScores = new ArrayList<>();
        bestFitness = new ArrayList<>();
        bestPrecision = new ArrayList<>();


        writer = null;
        try {
            String safeName = modelName.replaceAll("[/\\\\.]", "_");
            writer = new PrintWriter("." +
                    "/ils_" + safeName + ".csv");
            writer.println("iteration,fitness,precision,fscore,itime");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR - impossible to print the markovian abstraction.");
        }

        long eTime = System.currentTimeMillis();
        long iTime = System.currentTimeMillis();

        startTree(slog, order);
        bestFitness.add(currentAccuracy[0]);
        bestPrecision.add(currentAccuracy[1]);
        bestScores.add(currentAccuracy[2]);
        hits.add(iterations);
        bestSDFG = currentSDFG;
        bestTree = currentTree;

        // System.currentTimeMillis() - eTime < timeout &&
        while (System.currentTimeMillis() - eTime < timeout && iterations < maxit && currentSDFG != null) {
            try {

                if (currentAccuracy[2] > bestScores.get(bestScores.size() - 1)) {
                    System.out.println("INFO - improved fscore " + currentAccuracy[2]);
                    bestFitness.add(currentAccuracy[0]);
                    bestPrecision.add(currentAccuracy[1]);
                    bestScores.add(currentAccuracy[2]);
                    hits.add(iterations);
                    bestSDFG = currentSDFG;
                    bestTree = currentTree;
                    noImprovementCounter = 0;
                } else {
                    noImprovementCounter++;
                }

                iTime = System.currentTimeMillis() - iTime;

                /*if (noImprovementCounter >= maxIterationsBeforeRaise && order < maxK) {
                    // get traces that are in the model but not in the log and ectend with every rising of k
                    differences = staProcess.computeDifferences(staLog);

                    order++;
                    System.out.println("\u001B[32mINFO - No improvement for " + noImprovementCounter + " iterations, increasing k to " + order + "\u001B[0m");

                    // Recompute Log abstraction at new k
                    staLog = LogAbstraction.subtraceTree(slog, order);

                    // Re-evaluate best tree at new k
                    MarkovianBasedEvaluator reevaluateBest = new MarkovianBasedEvaluator(staLog, differences, slog, minerProxy, bestTree, order);
                    ExecutorService reevaluateService = Executors.newSingleThreadExecutor();
                    Future<Object[]> reevaluateResult = reevaluateService.submit(reevaluateBest);
                    Object[] newResult = reevaluateResult.get(timeout, TimeUnit.MILLISECONDS);
                    reevaluateService.shutdownNow();

                    currentAccuracy[0] = (Double) newResult[0];
                    currentAccuracy[1] = (Double) newResult[1];
                    currentAccuracy[2] = (Double) newResult[2];
                    staProcess = (SubtraceAbstraction) newResult[3];
                    currentTree = (EfficientTree) newResult[4];
                    ComputeTime += (long) newResult[5];
                    currentSDFG = bestSDFG;

                    noImprovementCounter = 0;
                }*/

                if (export)
                    AutomatedProcessDiscoveryOptimizer.exportTree(currentTree, ".\\ils_" + modelName + "_" + iterations + ".ptml");
                writer.println(iterations + "," + currentAccuracy[0] + "," + currentAccuracy[1] + "," + currentAccuracy[2] + "," + iTime);
                writer.flush();
                iterations++;
                iTime = System.currentTimeMillis();

                long mineStart = System.currentTimeMillis();
                if (currentAccuracy[1] > currentAccuracy[0]) {
/**     if precision is higher than fitness, we explore the DFGs having more edges.
 *      to do so, we select the most frequent edges of the markovian abstraction of the log that do not appear
 *      in the markovian abstraction of the process, NOTE: each edge is a subtrace.
 *      we select C*N subtraces, and we add C subtraces at a time to a copy of the current DFG.
 *      each of this copy is considered to be a neighbour of the current DFG with an improved fitness.
 *      for each of this copy we compute the f-score, and we retain the one with highest f-score.
 **/
                    staLog.computeDifferences(staProcess);
                    subtrace = staLog.nextMismatch();
                    tmpSDFG = new SimpleDirectlyFollowGraph(currentSDFG);
                    while (neighbours.size() != neighbourhood && subtrace != null) {
                        if (subtrace.isEmpty() && (subtrace = staLog.nextMismatch()) == null) break;

                        if ((subtrace = tmpSDFG.enhance(subtrace, 1)) == null) subtrace = staLog.nextMismatch();
                        else {
                            neighbours.add(tmpSDFG);
                            tmpSDFG = new SimpleDirectlyFollowGraph(currentSDFG);
                        }
                    }

                } else {
/**     if fitness is higher than precision, we explore the DFGs having less edges.
 *      to do so, we select random edges of the markovian abstraction of the process that do not appear
 *      in the markovian abstraction of the log.
 *      we select C*N subtraces, and we add C subtraces at a time to a copy of the current DFG.
 *      each of this copy is considered to be a neighbour of the current DFG with an improved fitness.
 *      for each of this copy we compute the f-score, and we retain the one with highest f-score.
 **/
                    staProcess.computeDifferences(staLog);
                    subtrace = staProcess.nextMismatch();
                    tmpSDFG = new SimpleDirectlyFollowGraph(currentSDFG);
                    while (neighbours.size() != neighbourhood && subtrace != null) {
                        if (subtrace.isEmpty() && (subtrace = staProcess.nextMismatch()) == null) break;

                        if ((subtrace = tmpSDFG.reduce(subtrace, 1)) == null) subtrace = staProcess.nextMismatch();
                        else {
                            neighbours.add(tmpSDFG);
                            tmpSDFG = new SimpleDirectlyFollowGraph(currentSDFG);
                        }
                    }
                }
                long mineEnd = System.currentTimeMillis();
                MineTime += (mineEnd - mineStart);

//                System.out.println("INFO - selected " + neighbours.size() + " neighbours.");

                if (neighbours.isEmpty()) {
//                    System.out.println("WARNING - empty neighbourhood " + neighbours.size() + " neighbours.");
                    while (!perturbTree(slog, order)) ;
                    continue;
                }

                multiThreadService = Executors.newFixedThreadPool(Math.min(32, neighbours.size()));
                for (SimpleDirectlyFollowGraph neighbourSDFG : neighbours) {
                    try {
                        long modifyStart = System.currentTimeMillis();
                        tmpTree = minerProxy.getTree(neighbourSDFG);
                        long modifyEnd = System.currentTimeMillis();
                        ModifyTime += (modifyEnd - modifyStart);
                    } catch (Exception e) {
                        System.out.println("WARNING - discarded one neighbour.");
                        continue;
                    }

                    evalThread = new MarkovianBasedEvaluator(staLog, slog, minerProxy, tmpTree, order);
                    evalResult = multiThreadService.submit(evalThread);

                    neighboursEvaluations.put(neighbourSDFG, evalResult);
                }

                sleep(minerProxy.getTimeout());

                improved = false;
                for (SimpleDirectlyFollowGraph neighbourSDFG : neighboursEvaluations.keySet()) {
                    evalResult = neighboursEvaluations.get(neighbourSDFG);
                    if (evalResult.isDone()) {
                        result = evalResult.get();

                        if ((Double) result[2] >= currentAccuracy[2]) {
                            currentAccuracy[0] = (Double) result[0];
                            currentAccuracy[1] = (Double) result[1];
                            currentAccuracy[2] = (Double) result[2];
                            staProcess = (SubtraceAbstraction) result[3];
                            currentTree = (EfficientTree) result[4];
                            ComputeTime += (long) result[5];
                            currentSDFG = neighbourSDFG;
                            improved = true;
                            icounter = 0;
                        }
                    } else {
                        evalResult.cancel(true);
                    }
                }

                neighbours.clear();
                neighboursEvaluations.clear();
                multiThreadService.shutdownNow();

/**     once we checked all the neighbours accuracies, we select the one improving the current state or none at all.
 *      if the one improving the current state, also improves the global maximum, we update that.
 */
                if (!improved && ++icounter == order) {
                    icounter = 0;
                    while (!perturbTree(slog, order)) ;
                }

            } catch (Exception e) {
                System.out.println("ERROR - I got tangled in the threads.");
                e.printStackTrace();
                while (!perturbTree(slog, order)) ;
            }
        }

        eTime = System.currentTimeMillis() - eTime;
        String hitrow = "";
        String fscorerow = "";
        for (int i = 0; i < hits.size(); i++) {
            hitrow += hits.get(i) + ",";
            fscorerow += bestScores.get(i) + ",";
        }

        writer.println(hitrow + (double) (eTime) / 1000.0);
        writer.println(fscorerow + (double) (eTime) / 1000.0);
        writer.close();

        System.out.println("\u001B[32mTotal Mine Time: " + MineTime + "ms\u001B[0m");
        System.out.println("\u001B[32mTotal Modify Time: " + ModifyTime + "ms\u001B[0m");
        System.out.println("\u001B[32mTotal Compute Time: " + ComputeTime + "ms\u001B[0m");

        System.out.println("\u001B[32mBest Fitness achieved: " + bestFitness.get(bestFitness.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mBest Precision achieved: " + bestPrecision.get(bestPrecision.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mBest F-score achieved: " + bestScores.get(bestScores.size() - 1) + "\u001B[0m");

        System.out.println("\u001B[32mTotal Iterations: " + iterations + "\u001B[0m");
        System.out.println("\u001B[32mFinal k value reached: " + order + "\u001B[0m");

        System.out.println("eTIME - " + (double) (eTime) / 1000.0 + "s");

        AcceptingPetriNet net = EfficientTree2AcceptingPetriNet.convert(bestTree);
        PnmlExportNetToPNML exporter = new PnmlExportNetToPNML();
        try {
            exporter.exportPetriNetToPNMLFile(new FakePluginContext(), net.getNet(), new File("C:\\Users\\Amer\\gitprojects\\bpmtk\\models\\ILSTree" + order + ".pnml"));
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }

        return bestTree;
    }

    private void start(SimpleLog slog, int order) {
        MarkovianBasedEvaluator markovianBasedEvaluator;
        ExecutorService executor = null;
        Future<Object[]> evalResult = null;
        BPMNDiagram tmpBPMN;
        Object[] result;

        try {
            // Modify Phase
            long modifyStart = System.currentTimeMillis();
            currentSDFG = minerProxy.restart(slog);
            tmpBPMN = minerProxy.getBPMN(currentSDFG);
            long modifyEnd = System.currentTimeMillis();
            ModifyTime = modifyEnd - modifyStart;

            markovianBasedEvaluator = new MarkovianBasedEvaluator(staLog, slog, minerProxy, tmpBPMN, order);
            executor = Executors.newSingleThreadExecutor();

            // Compute Phase
            evalResult = executor.submit(markovianBasedEvaluator);
            result = evalResult.get(minerProxy.getTimeout(), TimeUnit.MILLISECONDS);

            currentAccuracy[0] = (Double) result[0];
            currentAccuracy[1] = (Double) result[1];
            currentAccuracy[2] = (Double) result[2];
            staProcess = (SubtraceAbstraction) result[3];
            currentBPMN = (BPMNDiagram) result[4];
            ComputeTime += (long) result[5];

            executor.shutdownNow();
//            System.out.println("START - done.");
        } catch (TimeoutException e) {
            System.out.println("[TIMEOUT] Evaluation exceeded " + minerProxy.getTimeout() + " ms.");
            if (executor != null) {
                evalResult.cancel(true);
                executor.shutdownNow();
            }
            start(slog, order); // Retry
        } catch (Exception e) {
//            System.out.println("ERROR - start failed.");
            e.printStackTrace();
            if (executor != null) executor.shutdownNow();
            start(slog, order);
        }
    }

    private void startTree(SimpleLog slog, int order) {
        MarkovianBasedEvaluator markovianBasedEvaluator;
        ExecutorService executor = null;
        Future<Object[]> evalResult = null;
        EfficientTree tmpTree;
        Object[] result;

        try {
            // Modify Phase
            long modifyStart = System.currentTimeMillis();
            currentSDFG = minerProxy.restart(slog);
            tmpTree = minerProxy.getTree(currentSDFG);
            long modifyEnd = System.currentTimeMillis();
            ModifyTime = modifyEnd - modifyStart;

            markovianBasedEvaluator = new MarkovianBasedEvaluator(staLog, slog, minerProxy, tmpTree, order);
            executor = Executors.newSingleThreadExecutor();

            // Submit evaluation thread
            evalResult = executor.submit(markovianBasedEvaluator);
            // sleep(10000000);
            result = evalResult.get(minerProxy.getTimeout(), TimeUnit.MILLISECONDS);

            currentAccuracy[0] = (Double) result[0];
            currentAccuracy[1] = (Double) result[1];
            currentAccuracy[2] = (Double) result[2];
            staProcess = (SubtraceAbstraction) result[3];
            currentTree = (EfficientTree) result[4];
            ComputeTime += (long) result[5];

            executor.shutdownNow();
//            System.out.println("START - done.");
        } catch (TimeoutException e) {
            System.out.println("[TIMEOUT] Evaluation exceeded " + minerProxy.getTimeout() + " ms.");
            if (executor != null) {
                evalResult.cancel(true);
                executor.shutdownNow();
            }
            startTree(slog, order); // Retry
        } catch (Exception e) {
//            System.out.println("ERROR - start failed.");
            e.printStackTrace();
            if (executor != null) executor.shutdownNow();
            startTree(slog, order);
        }
    }

    private boolean perturb(SimpleLog slog, int order) {
        MarkovianBasedEvaluator markovianBasedEvaluator;
        ExecutorService executor;
        Future<Object[]> evalResult;
        BPMNDiagram tmpBPMN;
        Object[] result;
        SimpleDirectlyFollowGraph sdfg;
        SimpleDirectlyFollowGraph.PERTYPE pertype;

        try {
            perturbations++;

//            sdfg = new SimpleDirectlyFollowGraph(currentSDFG);
//            pertype = currentAccuracy[0] > currentAccuracy[1] ? SimpleDirectlyFollowGraph.PERTYPE.PREC : SimpleDirectlyFollowGraph.PERTYPE.FIT;
//            sdfg.perturb(PERTURBATION_STRENGTH, pertype);

            long modifyStart = System.currentTimeMillis();
            sdfg = minerProxy.perturb(slog, currentSDFG);
            tmpBPMN = minerProxy.getBPMN(sdfg);
            long modifyEnd = System.currentTimeMillis();
            ModifyTime = modifyEnd - modifyStart;

            markovianBasedEvaluator = new MarkovianBasedEvaluator(staLog, slog, minerProxy, tmpBPMN, order);
            executor = Executors.newSingleThreadExecutor();
            evalResult = executor.submit(markovianBasedEvaluator);

            sleep(minerProxy.getTimeout());
            if (evalResult.isDone()) {
                result = evalResult.get();
                currentAccuracy[0] = (Double) result[0];
                currentAccuracy[1] = (Double) result[1];
                currentAccuracy[2] = (Double) result[2];
                staProcess = (SubtraceAbstraction) result[3];
                currentBPMN = (BPMNDiagram) result[4];
                ComputeTime += (long) result[5];
                currentSDFG = sdfg;
//                System.out.println("PERTURBATION - done.");
                writer.println("p,p,p,p,p");
                executor.shutdownNow();
                return true;
            } else {
//                System.out.println("TIMEOUT - perturb failed.");
                evalResult.cancel(true);
                executor.shutdownNow();
                return false;
            }
        } catch (Exception e) {
//            System.out.println("EXCEPTION - perturb failed.");
            return false;
        }
    }

    private boolean perturbTree(SimpleLog slog, int order) {
        MarkovianBasedEvaluator markovianBasedEvaluator;
        ExecutorService executor;
        Future<Object[]> evalResult;
        EfficientTree tmpTree;
        Object[] result;
        SimpleDirectlyFollowGraph sdfg;
        SimpleDirectlyFollowGraph.PERTYPE pertype;

        try {
            perturbations++;

//            sdfg = new SimpleDirectlyFollowGraph(currentSDFG);
//            pertype = currentAccuracy[0] > currentAccuracy[1] ? SimpleDirectlyFollowGraph.PERTYPE.PREC : SimpleDirectlyFollowGraph.PERTYPE.FIT;
//            sdfg.perturb(PERTURBATION_STRENGTH, pertype);

            long modifyStart = System.currentTimeMillis();
            sdfg = minerProxy.perturb(slog, currentSDFG);
            tmpTree = minerProxy.getTree(sdfg);
            long modifyEnd = System.currentTimeMillis();
            ModifyTime = modifyEnd - modifyStart;

            markovianBasedEvaluator = new MarkovianBasedEvaluator(staLog, slog, minerProxy, tmpTree, order);
            executor = Executors.newSingleThreadExecutor();
            evalResult = executor.submit(markovianBasedEvaluator);

            sleep(minerProxy.getTimeout());
            if (evalResult.isDone()) {
                result = evalResult.get();
                currentAccuracy[0] = (Double) result[0];
                currentAccuracy[1] = (Double) result[1];
                currentAccuracy[2] = (Double) result[2];
                staProcess = (SubtraceAbstraction) result[3];
                currentTree = (EfficientTree) result[4];
                ComputeTime += (long) result[5];
                currentSDFG = sdfg;
//                System.out.println("PERTURBATION - done.");
                writer.println("p,p,p,p,p");
                executor.shutdownNow();
                return true;
            } else {
//                System.out.println("TIMEOUT - perturb failed.");
                evalResult.cancel(true);
                executor.shutdownNow();
                return false;
            }
        } catch (Exception e) {
//            System.out.println("EXCEPTION - perturb failed.");
            return false;
        }
    }
}