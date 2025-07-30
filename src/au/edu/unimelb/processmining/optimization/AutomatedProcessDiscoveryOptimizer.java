package au.edu.unimelb.processmining.optimization;

import au.edu.qut.processmining.log.LogParser;
import au.edu.qut.processmining.log.SimpleLog;
import au.edu.unimelb.processmining.accuracy.abstraction.mkAutomaton.MarkovianAutomatonAbstraction;
import com.raffaeleconforti.log.util.LogImporter;
import org.deckfour.xes.classification.XEventNameClassifier;
import org.deckfour.xes.factory.XFactoryNaiveImpl;
import org.deckfour.xes.model.XLog;
import org.processmining.acceptingpetrinet.models.AcceptingPetriNet;
import org.processmining.contexts.uitopia.UIContext;
import org.processmining.contexts.uitopia.UIPluginContext;
import org.processmining.framework.plugin.PluginContext;
import org.processmining.models.graphbased.directed.bpmn.BPMNDiagram;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree2AcceptingPetriNet;
import org.processmining.plugins.InductiveMiner.efficienttree.EfficientTree2processTree;
import org.processmining.plugins.InductiveMiner.plugins.EfficientTreeExportPlugin;
import org.processmining.plugins.bpmn.plugins.BpmnExportPlugin;
import org.processmining.plugins.kutoolbox.utils.FakePluginContext;
import org.processmining.plugins.pnml.exporting.PnmlExportNetToPNML;
import org.processmining.processtree.ProcessTree;
import org.processmining.processtree.ptml.exporting.PtmlExportTree;

import java.io.File;
import java.sql.Time;
import java.io.IOException;

public class AutomatedProcessDiscoveryOptimizer {

    private static int MAXIT = 10;
    private static int NEIGHBOURHOOD = 5;
    private static int TIMEOUT = 300000;

    public enum MetaOpt {RLS, ILS, TS, SA, RLSTree, ILSTree, TSTree, SATree}

    private MinerProxy minerProxy;
    private int order;
    private MetaOpt metaheuristics;
    private MinerProxy.MinerTAG miner;
    private SimpleLog slog;
    private BPMNDiagram bpmn;
    private EfficientTree tree;

    private String modelName;

    private Metaheuristics explorer;

    public AutomatedProcessDiscoveryOptimizer(int order, MetaOpt metaheuristics, MinerProxy.MinerTAG mtag) {
        this.order = order;
        this.metaheuristics = metaheuristics;
        this.miner = mtag;
    }

    public boolean init(String logPath) {
        XLog xlog;
        modelName = logPath.substring(logPath.lastIndexOf("/") + 1);
        modelName = modelName.substring(0, modelName.indexOf("."));
        if (!modelName.contains("PRT")) modelName = "PUB" + modelName;

//        System.out.println("MODEL NAME = " + modelName);

        try {
            xlog = LogImporter.importFromFile(new XFactoryNaiveImpl(), logPath);
            slog = LogParser.getSimpleLog(xlog, new XEventNameClassifier());
        } catch (Exception e) {
            System.out.println("ERROR - impossible to load the log");
            slog = null;
            return false;
        }
        minerProxy = new MinerProxy(miner, slog);
        return true;
    }

    public BPMNDiagram searchOptimalBPMN() throws Exception {

        switch (metaheuristics) {
            case RLS:
                explorer = new RepeatedLocalSearch(minerProxy);
                bpmn = explorer.searchOptimalSolution(slog, order, MAXIT, NEIGHBOURHOOD, TIMEOUT, modelName);
                break;
            case ILS:
                explorer = new IteratedLocalSearch(minerProxy);
                bpmn = explorer.searchOptimalSolution(slog, order, MAXIT, NEIGHBOURHOOD, TIMEOUT, modelName);
                break;
            case TS:
                explorer = new TabuSearch(minerProxy);
                bpmn = explorer.searchOptimalSolution(slog, order, MAXIT, NEIGHBOURHOOD, TIMEOUT, modelName);
                break;
            case SA:
                explorer = new SimulatedAnnealing(minerProxy);
                bpmn = explorer.searchOptimalSolution(slog, order, MAXIT, NEIGHBOURHOOD, TIMEOUT, modelName);
                break;
        }

        //exportBPMN(bpmn, ".\\models\\bpmn" + metaheuristics.toString() + order + ".bpmn");
        //exportBPMN(bpmn, "./" + metaheuristics.toString() + "_" + modelName + ".bpmn");

        return bpmn;
    }

    public EfficientTree searchOptimalTree() throws IOException {

        switch (metaheuristics) {
            case RLSTree:
                explorer = new RepeatedLocalSearch(minerProxy);
                tree = explorer.searchOptimalTree(slog, order, MAXIT, NEIGHBOURHOOD, TIMEOUT, modelName);
                break;
            case ILSTree:
                explorer = new IteratedLocalSearch(minerProxy);
                tree = explorer.searchOptimalTree(slog, order, MAXIT, NEIGHBOURHOOD, TIMEOUT, modelName);
                break;
            case TSTree:
                explorer = new TabuSearch(minerProxy);
                tree = explorer.searchOptimalTree(slog, order, MAXIT, NEIGHBOURHOOD, TIMEOUT, modelName);
                break;
            case SATree:
                explorer = new SimulatedAnnealing(minerProxy);
                tree = explorer.searchOptimalTree(slog, order, MAXIT, NEIGHBOURHOOD, TIMEOUT, modelName);
                break;
        }


        /*AcceptingPetriNet net = EfficientTree2AcceptingPetriNet.convert(tree);
        PnmlExportNetToPNML exporter = new PnmlExportNetToPNML();
        try {
            exporter.exportPetriNetToPNMLFile(new FakePluginContext(), net.getNet(), new File("C:\\Users\\Amer\\gitprojects\\bpmtk\\models\\ILSTree best.pnml"));
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }*/

        EfficientTree2processTree.convert(tree);
        exportTreeAsPTML(tree, ".\\models\\ptml" + metaheuristics.toString() + order + ".ptml");
        //exportTree(tree, "./" + metaheuristics.toString() + "_" + modelName + ".ptml");

        return tree;
    }

    public static void exportTreeAsPTML(EfficientTree et, String path) {
        // Convert EfficientTree to ProcessTree
        ProcessTree pt = EfficientTree2processTree.convert(et);

        // Create PTML exporter
        PtmlExportTree exporter = new PtmlExportTree();

        // Create plugin context
        UIContext ptUiContext = new UIContext();
        UIPluginContext pluginContext = ptUiContext.getMainPluginContext();
        try {
            // Export to PTML
            exporter.exportDefault(pluginContext, pt, new File(path));
        } catch (Exception e) {
            System.err.println("ERROR exporting tree to PTML: " + e.getMessage());
        }
    }


    public static void exportTree(EfficientTree tree, String path) {
        EfficientTreeExportPlugin efficientTreeExportPlugin = new EfficientTreeExportPlugin();
        UIContext etcontext = new UIContext();
        UIPluginContext etuiPluginContext = etcontext.getMainPluginContext();
        try {
            efficientTreeExportPlugin.exportDefault(etuiPluginContext, tree, new File(path));
        } catch (Exception e) {
            System.out.println("ERROR - impossible to export the Tree");
        }
    }

    public static void exportBPMN(BPMNDiagram diagram, String path) {
        BpmnExportPlugin bpmnExportPlugin = new BpmnExportPlugin();
        UIContext bpmncontext = new UIContext();
        UIPluginContext bpmnuiPluginContext = bpmncontext.getMainPluginContext();
        try {
            bpmnExportPlugin.export(bpmnuiPluginContext, diagram, new File(path));
        } catch (Exception e) {
            System.out.println("ERROR - impossible to export the BPMN");
        }
    }
}
