package au.edu.unimelb.processmining.optimization;

import au.edu.qut.processmining.log.SimpleLog;
import au.edu.unimelb.processmining.accuracy.abstraction.mkAutomaton.MarkovianAutomatonAbstraction;
import au.edu.unimelb.processmining.accuracy.abstraction.subtrace.SubtraceAbstraction;
import com.aliasi.util.Strings;
import org.processmining.models.graphbased.directed.bpmn.BPMNDiagram;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree;
import org.python.antlr.ast.Str;

import java.util.ArrayList;
import java.util.concurrent.Callable;

public class MarkovianBasedEvaluator implements Callable<Object[]> {

    private SimpleLog slog;
    private SubtraceAbstraction staLog;
    private MinerProxy proxy;

    private EfficientTree tree;
    private BPMNDiagram bpmn;
    private ArrayList<String> differences;

    //private SimpleDirectlyFollowGraph sdfg;
    private int order;

    public MarkovianBasedEvaluator(SubtraceAbstraction staLog, SimpleLog slog, MinerProxy minerProxy, BPMNDiagram bpmn, int order) {
        this.staLog = staLog;
        this.proxy = minerProxy;
        this.bpmn = bpmn;
        this.order = order;
        this.slog = slog;
    }

    public MarkovianBasedEvaluator(SubtraceAbstraction staLog, SimpleLog slog, MinerProxy minerProxy, EfficientTree tree, int order) {
        this.staLog = staLog;
        this.proxy = minerProxy;
        this.tree = tree;
        this.order = order;
        this.slog = slog;
    }

    public MarkovianBasedEvaluator(SubtraceAbstraction staLog, ArrayList<String> differences, SimpleLog slog, MinerProxy minerProxy, BPMNDiagram bpmn, int order) {
        this.staLog = staLog;
        this.proxy = minerProxy;
        this.bpmn = bpmn;
        this.order = order;
        this.slog = slog;
        this.differences = differences;
    }

    public MarkovianBasedEvaluator(SubtraceAbstraction staLog, ArrayList<String> differences, SimpleLog slog, MinerProxy minerProxy, EfficientTree tree, int order) {
        this.staLog = staLog;
        this.proxy = minerProxy;
        this.tree = tree;
        this.order = order;
        this.slog = slog;
        this.differences = differences;
    }

    @Override
    public Object[] call() {
        SubtraceAbstraction staProcess;
        Object[] results = new Object[6];

        long start = System.currentTimeMillis();

        try {
            if (tree != null) {
                // 1. Compute Mk-automaton
                MarkovianAutomatonAbstraction abstraction = new MarkovianAutomatonAbstraction(tree, order, slog);

                // 2. Convert automaton to SubtraceAbstraction using internal label mapping
                staProcess = SubtraceAbstraction.abstractProcessBehaviour(abstraction.getAutomaton().getFiniteStrings(), order, abstraction.getCharToIDs());
            } else if (bpmn != null) {
                staProcess = SubtraceAbstraction.abstractProcessBehaviour(this.bpmn, order, slog);
            } else {
                throw new IllegalStateException("Neither tree nor BPMN is initialized in MarkovianBasedEvaluator.");
            }

            if (differences != null) {
                for (String dif : differences) {
                    staProcess.removeUnusedSubtraces(dif);
                }
            }

            if (staProcess == null) return new Object[]{0.0, 0.0, 0.0, null, tree != null ? tree : bpmn};

            double fitness = staLog.computeMAFitness(staProcess);
            double precision = staProcess.computeMAPrecision(staLog);
            double fscore = (fitness + precision == 0) ? 0.0 : (2.0 * fitness * precision) / (fitness + precision);

            results[0] = fitness;
            results[1] = precision;
            results[2] = fscore;
            results[3] = staProcess;
            results[4] = tree != null ? tree : bpmn;

        } catch (Exception | Error e) {
            results[0] = results[1] = results[2] = 0.0;
            results[3] = null;
            results[4] = tree != null ? tree : bpmn;
        }

        long end = System.currentTimeMillis();
        results[5] = end - start;

        return results;
    }
}
