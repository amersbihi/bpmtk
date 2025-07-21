package au.edu.unimelb.processmining.optimization;

import au.edu.qut.processmining.log.SimpleLog;
import org.processmining.models.graphbased.directed.bpmn.BPMNDiagram;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree;

public interface Metaheuristics {
    BPMNDiagram searchOptimalSolution(SimpleLog slog, int order, int maxit, int neighbourhood, int timeout, String name) throws Exception;

    EfficientTree searchOptimalTree(SimpleLog slog, int order, int maxit, int neighbourhood, int timeout, String name);

}
