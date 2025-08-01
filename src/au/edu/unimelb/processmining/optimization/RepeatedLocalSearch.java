package au.edu.unimelb.processmining.optimization;

import au.edu.qut.processmining.log.SimpleLog;
import au.edu.unimelb.processmining.accuracy.abstraction.LogAbstraction;
import au.edu.unimelb.processmining.accuracy.abstraction.subtrace.SubtraceAbstraction;
import org.processmining.models.graphbased.directed.bpmn.BPMNDiagram;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree;

import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;

import static java.lang.Thread.sleep;

public class RepeatedLocalSearch implements Metaheuristics {

    private MinerProxy minerProxy;

    private BPMNDiagram currentBPMN;
    private BPMNDiagram bestBPMN;

    private SimpleDirectlyFollowGraph currentSDFG;
    private SimpleDirectlyFollowGraph bestSDFG;

    private EfficientTree currentTree;
    private EfficientTree bestTree;
    private ArrayList<Double> bestFitness;
    private ArrayList<Double> bestPrecision;
    private ArrayList<Double> bestScores;
    private ArrayList<Integer> hits;
    Double[] currentAccuracy = new Double[3];

    private SubtraceAbstraction staLog;
    private SubtraceAbstraction staProcess;

    private PrintWriter writer;
    private int restarts;

    int noImprovementCounter = 0;
    final int maxIterationsBeforeRaise = 10;
    final int maxK = 5;
    private long MineTime;
    private long ModifyTime;
    private long ComputeTime;

    public RepeatedLocalSearch(MinerProxy proxy) {
        minerProxy = proxy;
    }

    public BPMNDiagram searchOptimalSolution(SimpleLog slog, int order, int maxit, int neighbourhood, int timeout, String modelName) {
        int iterations = 0;
        int icounter = 0;
        boolean improved;
        boolean export = false;

        restarts = 0;
        staLog = LogAbstraction.subtrace(slog, order);

        ExecutorService multiThreadService;
        MarkovianBasedEvaluator evalThread;
        Future<Object[]> evalResult;
        Map<SimpleDirectlyFollowGraph, Future<Object[]>> neighboursEvaluations = new HashMap<>();
        String subtrace;
        Set<SimpleDirectlyFollowGraph> neighbours = new HashSet<>();
        Object[] result;

        SimpleDirectlyFollowGraph tmpSDFG;
        BPMNDiagram tmpBPMN;

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
            System.out.println("ERROR - impossible to print the markovian abstraction.");
        }

        long eTime = System.currentTimeMillis();
        long iTime = System.currentTimeMillis();

        restart(slog, order);
        bestFitness.add(currentAccuracy[0]);
        bestPrecision.add(currentAccuracy[1]);
        bestScores.add(currentAccuracy[2]);
        hits.add(iterations);
        bestSDFG = currentSDFG;
        bestBPMN = currentBPMN;

        while (System.currentTimeMillis() - eTime < timeout && iterations < maxit && currentSDFG != null) {
            try {

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

                if (noImprovementCounter >= maxIterationsBeforeRaise && order < maxK) {
                    order++;
                    System.out.println("\u001B[32mINFO - No improvement for " + noImprovementCounter + " iterations, increasing k to " + order + "\u001B[0m");

                    // Recompute Log abstraction at new k
                    staLog = LogAbstraction.subtrace(slog, order);

                    // Re-evaluate best tree at new k
                    MarkovianBasedEvaluator reevaluateBest = new MarkovianBasedEvaluator(staLog, slog, minerProxy, bestBPMN, order);
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
                }

                iTime = System.currentTimeMillis() - iTime;
                if (export)
                    AutomatedProcessDiscoveryOptimizer.exportBPMN(currentBPMN, ".\\rls_" + modelName + "_" + iterations + ".bpmn");
                writer.println(iterations + "," + currentAccuracy[0] + "," + currentAccuracy[1] + "," + currentAccuracy[2] + "," + iTime);
//                System.out.println(iterations + "," + currentAccuracy[0] + "," + currentAccuracy[1] + "," + currentAccuracy[2] + "," + iTime);
                writer.flush();
                iterations++;
                iTime = System.currentTimeMillis();

                long mineStart = System.currentTimeMillis();
                if (currentAccuracy[1] > currentAccuracy[0]) {
/**     if precision is higher than fitness, we explore the DFGs having more edges.
 *      to do so, we select the most frequent edges of the markovian abstraction of the log that do not appear
 *      in the markovian abstraction of the process, NOTE: each edge is a subtrace.
 *      we select C*N subtraces and we add C subtraces at a time to a copy of the current DFG.
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
 *      we select C*N subtraces and we add C subtraces at a time to a copy of the current DFG.
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

                // System.out.println("INFO - selected " + neighbours.size() + " neighbours.");

                if (neighbours.isEmpty()) {
//                    System.out.println("WARNING - empty neighbourhood " + neighbours.size() + " neighbours.");
                    restart(slog, order);
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
//                    System.out.println("INFO - exploring 1 neighbour.");
                }

//                System.out.println("INFO - synchronising with threads.");
                sleep(minerProxy.getTimeout());

                improved = false;
                int done = 0;
                int cancelled = 0;
                for (SimpleDirectlyFollowGraph neighbourSDFG : neighboursEvaluations.keySet()) {
                    evalResult = neighboursEvaluations.get(neighbourSDFG);
                    if (evalResult.isDone()) {
                        done++;
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
                        cancelled++;
                        evalResult.cancel(true);
                    }
                }

                System.out.println("DONE - " + done);
//                System.out.println("CANCELLED - " + cancelled);

                neighbours.clear();
                neighboursEvaluations.clear();
                multiThreadService.shutdownNow();

/**     once we checked all the neighbours accuracies, we select the one improving the current state or none at all.
 *      if the one improving the current state, also improves the global maximum, we update that.
 */
                if (!improved && ++icounter == order) {
                    icounter = 0;
                    restart(slog, order);
                }

            } catch (Exception e) {
                System.out.println("ERROR - I got tangled in the threads.");
                e.printStackTrace();
                restart(slog, order);
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

        // Print summary
        System.out.println("\u001B[32mTotal Mine Time: " + MineTime + "ms\u001B[0m");
        System.out.println("\u001B[32mTotal Modify Time: " + ModifyTime + "ms\u001B[0m");
        System.out.println("\u001B[32mTotal Compute Time: " + ComputeTime + "ms\u001B[0m");
        System.out.println("\u001B[32mBest Fitness achieved: " + bestFitness.get(bestFitness.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mBest Precision achieved: " + bestPrecision.get(bestPrecision.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mBest F-score achieved: " + bestScores.get(bestScores.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mTotal Iterations: " + iterations + "\u001B[0m");
        System.out.println("\u001B[32mFinal k value reached: " + order + "\u001B[0m");
        System.out.println("eTIME - " + (double) (eTime) / 1000.0 + "s");
        System.out.println("STATS - total restarts: " + restarts);

        return bestBPMN;
    }

    @Override
    public EfficientTree searchOptimalTree(SimpleLog slog, int order, int maxit, int neighbourhood, int timeout, String modelName) {
        int iterations = 0;
        int icounter = 0;
        boolean improved;
        boolean export = false;

        restarts = 0;
        staLog = LogAbstraction.subtraceTree(slog, order);

        ExecutorService multiThreadService;
        MarkovianBasedEvaluator evalThread;
        Future<Object[]> evalResult;
        Map<SimpleDirectlyFollowGraph, Future<Object[]>> neighboursEvaluations = new HashMap<>();
        String subtrace;
        Set<SimpleDirectlyFollowGraph> neighbours = new HashSet<>();
        Object[] result;

        SimpleDirectlyFollowGraph tmpSDFG;
        EfficientTree tmpTree;

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
            System.out.println("ERROR - impossible to print the markovian abstraction.");
        }

        long eTime = System.currentTimeMillis();
        long iTime = System.currentTimeMillis();

        restartTree(slog, order);
        bestFitness.add(currentAccuracy[0]);
        bestPrecision.add(currentAccuracy[1]);
        bestScores.add(currentAccuracy[2]);
        hits.add(iterations);
        bestSDFG = currentSDFG;
        bestTree = currentTree;

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

                if (noImprovementCounter >= maxIterationsBeforeRaise && order < maxK) {
                    order++;
                    System.out.println("\u001B[32mINFO - No improvement for " + noImprovementCounter + " iterations, increasing k to " + order + "\u001B[0m");

                    // Recompute Log abstraction at new k
                    staLog = LogAbstraction.subtraceTree(slog, order);

                    // Re-evaluate best tree at new k
                    MarkovianBasedEvaluator reevaluateBest = new MarkovianBasedEvaluator(staLog, slog, minerProxy, bestTree, order);
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
                }

                iTime = System.currentTimeMillis() - iTime;

                if (export)
                    AutomatedProcessDiscoveryOptimizer.exportTree(currentTree, ".\\rls_" + modelName + "_" + iterations + ".ptml");
                writer.println(iterations + "," + currentAccuracy[0] + "," + currentAccuracy[1] + "," + currentAccuracy[2] + "," + iTime);
//                System.out.println(iterations + "," + currentAccuracy[0] + "," + currentAccuracy[1] + "," + currentAccuracy[2] + "," + iTime);
                writer.flush();
                iterations++;
                iTime = System.currentTimeMillis();

                long mineStart = System.currentTimeMillis();
                if (currentAccuracy[1] > currentAccuracy[0]) {
/**     if precision is higher than fitness, we explore the DFGs having more edges.
 *      to do so, we select the most frequent edges of the markovian abstraction of the log that do not appear
 *      in the markovian abstraction of the process, NOTE: each edge is a subtrace.
 *      we select C*N subtraces and we add C subtraces at a time to a copy of the current DFG.
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
 *      we select C*N subtraces and we add C subtraces at a time to a copy of the current DFG.
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

                // System.out.println("INFO - selected " + neighbours.size() + " neighbours.");

                if (neighbours.isEmpty()) {
//                    System.out.println("WARNING - empty neighbourhood " + neighbours.size() + " neighbours.");
                    restartTree(slog, order);
                    continue;
                }

                multiThreadService = Executors.newFixedThreadPool(neighbours.size());
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

                    // time for computation in MarkovianBasedEvaluator
                    evalThread = new MarkovianBasedEvaluator(staLog, slog, minerProxy, tmpTree, order);
                    evalResult = multiThreadService.submit(evalThread);
                    neighboursEvaluations.put(neighbourSDFG, evalResult);
//                    System.out.println("INFO - exploring 1 neighbour.");
                }

//                System.out.println("INFO - synchronising with threads.");
                sleep(minerProxy.getTimeout());

                improved = false;
                int done = 0;
                int cancelled = 0;
                for (SimpleDirectlyFollowGraph neighbourSDFG : neighboursEvaluations.keySet()) {
                    evalResult = neighboursEvaluations.get(neighbourSDFG);
                    if (evalResult.isDone()) {
                        done++;
                        result = evalResult.get();
                        if ((Double) result[2] >= currentAccuracy[2]) {
                            currentAccuracy[0] = (Double) result[0];
                            currentAccuracy[1] = (Double) result[1];
                            currentAccuracy[2] = (Double) result[2];
                            staProcess = (SubtraceAbstraction) result[3];
                            currentTree = (EfficientTree) result[4];
                            currentSDFG = neighbourSDFG;
                            improved = true;
                            icounter = 0;
                        }
                    } else {
                        cancelled++;
                        evalResult.cancel(true);
                    }
                }

                System.out.println("DONE - " + done);
//                System.out.println("CANCELLED - " + cancelled);

                neighbours.clear();
                neighboursEvaluations.clear();
                multiThreadService.shutdownNow();

/**     once we checked all the neighbours accuracies, we select the one improving the current state or none at all.
 *      if the one improving the current state, also improves the global maximum, we update that.
 */
                if (!improved && ++icounter == order) {
                    icounter = 0;
                    restartTree(slog, order);
                }

            } catch (Exception e) {
                System.out.println("ERROR - I got tangled in the threads.");
                e.printStackTrace();
                restartTree(slog, order);
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

        // Print summary
        System.out.println("\u001B[32mTotal Mine Time: " + MineTime + "ms\u001B[0m");
        System.out.println("\u001B[32mTotal Modify Time: " + ModifyTime + "ms\u001B[0m");
        System.out.println("\u001B[32mTotal Compute Time: " + ComputeTime + "ms\u001B[0m");
        System.out.println("\u001B[32mBest Fitness achieved: " + bestFitness.get(bestFitness.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mBest Precision achieved: " + bestPrecision.get(bestPrecision.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mBest F-score achieved: " + bestScores.get(bestScores.size() - 1) + "\u001B[0m");
        System.out.println("\u001B[32mTotal Iterations: " + iterations + "\u001B[0m");
        System.out.println("\u001B[32mFinal k value reached: " + order + "\u001B[0m");
        System.out.println("eTIME - " + (double) (eTime) / 1000.0 + "s");
        System.out.println("STATS - total restarts: " + restarts);

        return bestTree;
    }


    private void restart(SimpleLog slog, int order) {
        MarkovianBasedEvaluator markovianBasedEvaluator;
        ExecutorService executor = null;
        Future<Object[]> evalResult;
        BPMNDiagram tmpBPMN;
        Object[] result;

//        System.out.println("RESTART - starting...");

        try {
            restarts++;
            long modifyStart = System.currentTimeMillis();
            currentSDFG = minerProxy.restart(slog);
            if (currentSDFG == null) return;
            tmpBPMN = minerProxy.getBPMN(currentSDFG);
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
                executor.shutdownNow();
                System.out.println("RESTART - done.");
//                AutomatedProcessDiscoveryOptimizer.exportBPMN(currentBPMN, ".\\fo-test.bpmn");
                writer.println("r,r,r,r,r");
            } else {
                System.out.println("TIMEOUT - restart failed.");
                evalResult.cancel(true);
                executor.shutdownNow();
                restart(slog, order);
            }
        } catch (Exception e) {
            System.out.println("WARNING - restart failed.");
            e.printStackTrace();
            if (executor != null) executor.shutdownNow();
            restart(slog, order);
        }
    }

    private void restartTree(SimpleLog slog, int order) {
        MarkovianBasedEvaluator markovianBasedEvaluator;
        ExecutorService executor = null;
        Future<Object[]> evalResult;
        EfficientTree tmpTree;
        Object[] result;

//        System.out.println("RESTART - starting...");

        try {
            restarts++;
            long modifyStart = System.currentTimeMillis();
            currentSDFG = minerProxy.restart(slog);
            if (currentSDFG == null) return;
            tmpTree = minerProxy.getTree(currentSDFG);
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
                executor.shutdownNow();
                System.out.println("RESTART - done.");
//                AutomatedProcessDiscoveryOptimizer.exportBPMN(currentBPMN, ".\\fo-test.bpmn");
                writer.println("r,r,r,r,r");
            } else {
                System.out.println("TIMEOUT - restart failed.");
                evalResult.cancel(true);
                executor.shutdownNow();
                restartTree(slog, order);
            }
        } catch (Exception e) {
            System.out.println("WARNING - restart failed.");
            e.printStackTrace();
            if (executor != null) executor.shutdownNow();
            restartTree(slog, order);
        }
    }

}
