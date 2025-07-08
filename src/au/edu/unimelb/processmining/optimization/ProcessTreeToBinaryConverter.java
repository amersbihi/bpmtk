package au.edu.unimelb.processmining.optimization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.processmining.processtree.Block;
import org.processmining.processtree.Node;
import org.processmining.processtree.ProcessTree;
import org.processmining.processtree.impl.AbstractBlock;
import org.processmining.processtree.impl.ProcessTreeImpl;

public class ProcessTreeToBinaryConverter {

    /**
     * Converts a process tree to a binary process tree where each node has at most 2 children.
     * Nodes with more than 2 children are restructured by keeping the first child as the left child
     * and creating a new node with the same operator (or XOR for LOOP) containing the remaining children.
     *
     * @param tree The process tree to convert
     * @return The converted binary process tree
     */
    public static ProcessTree processTreeToBinaryProcessTree(ProcessTree tree) {
        if (tree.getRoot() != null) {
            processTreeToBinaryProcessTree(tree.getRoot());
        }
        return tree;
    }

    /**
     * Recursively converts a node and its children to binary form.
     *
     * @param node The node to convert
     */
    private static void processTreeToBinaryProcessTree(Node node) {
        if (node == null || !(node instanceof Block)) {
            return; // Leaf nodes or null nodes don't need conversion
        }

        Block block = (Block) node;
        List<Node> children = new ArrayList<>(block.getChildren());

        // If this block has more than 2 children, restructure it
        if (children.size() > 2) {
            Node leftChild = children.get(0);
            if (leftChild == null) {
                return; // Skip if first child is null
            }

            // Create a new block with the same operator type for the remaining children
            if(block instanceof Block.XorLoop || block instanceof Block.DefLoop) {
                Block rightBlock = new AbstractBlock.Xor(UUID.randomUUID(), block.getName());
            }
            Block rightBlock = createBlockOfSameType(block);
            if (rightBlock == null) {
                return; // Skip if we couldn't create the right block
            }

            // Add the new block to the process tree first
            if (node.getProcessTree() != null) {
                node.getProcessTree().addNode(rightBlock);
                rightBlock.setProcessTree(node.getProcessTree());
            }

            // Add remaining children to the new right block
            for (int i = 1; i < children.size(); i++) {
                Node child = children.get(i);
                if (child != null) {
                    rightBlock.addChild(child);
                }
            }

            // Clear existing children and add the binary structure
            clearBlockChildren(block);
            block.addChild(leftChild);
            block.addChild(rightBlock);
        }

        // Recursively process all children
        for (Node child : new ArrayList<>(block.getChildren())) {
            if (child != null) {
                processTreeToBinaryProcessTree(child);
            }
        }
    }

    /**
     * Creates a new block with the same type as the original block.
     * For LOOP blocks, creates an XOR block instead.
     *
     * @param originalBlock The original block to copy the type from
     * @return A new block of the appropriate type
     */
    private static Block createBlockOfSameType(Block originalBlock) {
        UUID newId = UUID.randomUUID();
        String name = originalBlock.getName() + "_binary";

        // If it's a LOOP block, create XOR instead
        if (originalBlock instanceof Block.XorLoop || originalBlock instanceof Block.DefLoop) {
            return new AbstractBlock.Xor(newId, name);
        }

        // Otherwise, create the same type
        if (originalBlock instanceof Block.Seq) {
            return new AbstractBlock.Seq(newId, name);
        } else if (originalBlock instanceof Block.And) {
            return new AbstractBlock.And(newId, name);
        } else if (originalBlock instanceof Block.Xor) {
            return new AbstractBlock.Xor(newId, name);
        } else if (originalBlock instanceof Block.Or) {
            return new AbstractBlock.Or(newId, name);
        } else if (originalBlock instanceof Block.Def) {
            return new AbstractBlock.Def(newId, name);
        } else if (originalBlock instanceof Block.PlaceHolder) {
            return new AbstractBlock.PlaceHolder(newId, name);
        } else {
            // Default fallback - create XOR block
            return new AbstractBlock.Xor(newId, name);
        }
    }

    /**
     * Removes all children from a block by removing the outgoing edges.
     *
     * @param block The block to clear children from
     */
    private static void clearBlockChildren(Block block) {
        // Create a copy of the outgoing edges to avoid concurrent modification
        List<org.processmining.processtree.Edge> edgesToRemove =
                new ArrayList<>(block.getOutgoingEdges());

        for (org.processmining.processtree.Edge edge : edgesToRemove) {
            block.removeOutgoingEdge(edge);
            edge.getTarget().removeIncomingEdge(edge);
            block.getProcessTree().removeEdge(edge);
        }
    }

    /**
     * Example usage method
     */
    public static void main(String[] args) {
        // Create a sample process tree
        ProcessTree tree = new ProcessTreeImpl("SampleTree");

        // Convert to binary process tree
        ProcessTree binaryTree = processTreeToBinaryProcessTree(tree);

        System.out.println("Converted process tree to binary form: " + binaryTree);
    }
}
