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
    private ArrayList<Integer> hits;
    Double[] currentAccuracy = new Double[3];

    private SubtraceAbstraction staLog;
    private SubtraceAbstraction staProcess;

    private PrintWriter writer;
    private int perturbations;

    private long MineTime;
    private long ModifyTime;
    private long ComputeTime;

    public IteratedLocalSearch(MinerProxy proxy) {
        minerProxy = proxy;
    }

    public SimpleDirectlyFollowGraph getBestSDFG() {
        return bestSDFG;
    }

    public BPMNDiagram searchOptimalSolution(SimpleLog slog, int order, int maxit, int neighbourhood, int timeout, String modelName) {
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

        SimpleDirectlyFollowGraph tmpSDFG;
        BPMNDiagram tmpBPMN;

        boolean improved;
        boolean export = false;

        hits = new ArrayList<>();
        bestScores = new ArrayList<>();

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
        bestScores.add(currentAccuracy[2]);
        hits.add(iterations);
        bestSDFG = currentSDFG;
        bestBPMN = currentBPMN;

        while (System.currentTimeMillis() - eTime < timeout && iterations < maxit && currentSDFG != null) {
            try {

                if (currentAccuracy[2] > bestScores.get(bestScores.size() - 1)) {
                    System.out.println("INFO - improved fscore " + currentAccuracy[2]);
                    bestScores.add(currentAccuracy[2]);
                    hits.add(iterations);
                    bestSDFG = currentSDFG;
                    bestBPMN = currentBPMN;
                }

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

                sleep(0);

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

        System.out.println("Total Mine Time: " + MineTime + "ms");
        System.out.println("Total Modify Time: " + ModifyTime + "ms");
        System.out.println("Total Compute Time: " + ComputeTime + "ms");

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

        System.out.println("eTIME - " + (double) (eTime) / 1000.0 + "s");
//        System.out.println("STATS - total perturbations: " + perturbations);

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

        SimpleDirectlyFollowGraph tmpSDFG;
        EfficientTree tmpTree;

        boolean improved;
        boolean export = false;

        hits = new ArrayList<>();
        bestScores = new ArrayList<>();

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
        bestScores.add(currentAccuracy[2]);
        hits.add(iterations);
        bestSDFG = currentSDFG;
        bestTree = currentTree;

        while (System.currentTimeMillis() - eTime < timeout && iterations < maxit && currentSDFG != null) {
            try {

                if (currentAccuracy[2] > bestScores.get(bestScores.size() - 1)) {
                    System.out.println("INFO - improved fscore " + currentAccuracy[2]);
                    bestScores.add(currentAccuracy[2]);
                    hits.add(iterations);
                    bestSDFG = currentSDFG;
                    bestTree = currentTree;
                }

                iTime = System.currentTimeMillis() - iTime;
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

                sleep(0);

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
                    while (!perturb(slog, order)) ;
                }

            } catch (Exception e) {
                System.out.println("ERROR - I got tangled in the threads.");
                e.printStackTrace();
                while (!perturbTree(slog, order)) ;
            }
        }

        System.out.println("Total Mine Time: " + MineTime + "ms");
        System.out.println("Total Modify Time: " + ModifyTime + "ms");
        System.out.println("Total Compute Time: " + ComputeTime + "ms");

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

        System.out.println("eTIME - " + (double) (eTime) / 1000.0 + "s");
//        System.out.println("STATS - total perturbations: " + perturbations);

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
            ComputeTime = (long) result[5];

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
            result = evalResult.get(minerProxy.getTimeout(), TimeUnit.MILLISECONDS);

            currentAccuracy[0] = (Double) result[0];
            currentAccuracy[1] = (Double) result[1];
            currentAccuracy[2] = (Double) result[2];
            staProcess = (SubtraceAbstraction) result[3];
            currentTree = (EfficientTree) result[4];
            ComputeTime = (long) result[5];

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
                ComputeTime = (long) result[5];
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
