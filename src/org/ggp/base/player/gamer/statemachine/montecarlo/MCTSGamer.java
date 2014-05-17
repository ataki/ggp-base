package org.ggp.base.player.gamer.statemachine.montecarlo;

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.Random;

import javax.swing.JPanel;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import javax.swing.JTextField;
import javax.swing.JLabel;

import org.ggp.base.apps.player.detail.DetailPanel;
import org.ggp.base.apps.player.config.ConfigPanel;
import org.ggp.base.apps.player.detail.SimpleDetailPanel;
import org.ggp.base.player.gamer.exception.GamePreviewException;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.util.game.Game;
import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.cache.CachedStateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;

import org.ggp.base.util.logging.GamerLogger;

public class MCTSGamer extends StateMachineGamer
{
    private int depth_limit = 1;
    private List<Role> gameRoles = null;
    private Map<Role, Integer> roleIndices = null;
    private Role opponent = null;

    private Move bestMoveSoFar = null;
    
    private Long timeout_buffer = 500L; // In milliseconds

    private Integer numProbes = 20;

	private HashMap<MachineState, MCTSNode> nodeMap = null;

	private Integer moveSelection = 1;

	private Double memoryThreshold = 80.0;

	private boolean expandNewNodes = true;

	private long numExpansions = 0;

	private class MCTSNode {
		MachineState state;
		int visits;
		double totalScore;
		double opponentScore;
		HashMap<Move, ArrayList<MCTSNode>> children;
		MCTSNode parent;
		Move 	 parentMove;

		public MCTSNode(MachineState s, int v, double score, double oScore, MCTSNode p, Move m) {
			this.state = s;
			this.visits = v;
			this.totalScore = score;
			this.opponentScore = oScore;
			this.children = new HashMap<Move, ArrayList<MCTSNode>>();
			this.parent = p;
			this.parentMove = m;
		}

		public MCTSNode(MachineState s, MCTSNode p, Move m) {
			this(s,0,0.0,0.0,p,m);
		}

		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("State: "+state+"\n");
			sb.append("Visits: "+visits+"\n");
			sb.append("TotalScore: "+totalScore+"\t"+"oScore:"+opponentScore+"\n");
			sb.append("Num children: "+children.size()+"\n");
			sb.append("Parent Move: "+parentMove+"\n");

			return sb.toString();
		}

		public void addChild(MCTSNode child) {
			ArrayList<MCTSNode> siblings = children.get(child.parentMove);
		
			if (siblings == null) {
				siblings = new ArrayList<MCTSNode>();
				siblings.add(child);
				children.put(child.parentMove, siblings);
			} else {
				siblings.add(child);
			}
		}

	}

	private void log(String s) {
		GamerLogger.log("MCTSGamer", s);
	}

    @SuppressWarnings("serial")
    private class MCTSConfigPanel extends ConfigPanel implements ActionListener {

        private final JTextField numProbesField;
        private final JTextField timeoutBufferField;
		private final JTextField memoryThresholdField;

        private final MCTSGamer gamer;

        public MCTSConfigPanel(MCTSGamer g) {
            super(new GridBagLayout());

            this.gamer = g;
            
            numProbesField = new JTextField(gamer.numProbes.toString());
            timeoutBufferField = new JTextField(gamer.timeout_buffer.toString());
        	memoryThresholdField = new JTextField(gamer.memoryThreshold.toString());

            numProbesField.addActionListener(this);
            timeoutBufferField.addActionListener(this);
			memoryThresholdField.addActionListener(this);

            this.add(new JLabel("Number of probes:"), new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0, GridBagConstraints.EAST, GridBagConstraints.NONE, new Insets(20, 5, 5, 5), 5, 5));
            this.add(numProbesField, new GridBagConstraints(1, 0, 1, 1, 1.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL, new Insets(20, 5, 5, 5), 5, 5));
            this.add(new JLabel("Timeout Buffer:"), new GridBagConstraints(0, 1, 1, 1, 0.0, 0.0, GridBagConstraints.EAST, GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
            this.add(timeoutBufferField, new GridBagConstraints(1, 1, 1, 1, 1.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH, new Insets(5, 5, 5, 5), 5, 5));
            this.add(new JLabel("Memory Threshold(%):"), new GridBagConstraints(0, 2, 1, 1, 0.0, 0.0, GridBagConstraints.EAST, GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
            this.add(memoryThresholdField, new GridBagConstraints(1, 2, 1, 1, 1.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH, new Insets(5, 5, 5, 5), 5, 5));

        }

        @Override
        public void actionPerformed(ActionEvent e) {
            
			try {
				if (e.getSource() == numProbesField) {
					gamer.numProbes = Integer.valueOf(numProbesField.getText());
				} else if (e.getSource() == timeoutBufferField) {
					gamer.timeout_buffer = Long.valueOf(timeoutBufferField.getText());
				} else if (e.getSource() == memoryThresholdField) {
					System.out.println("Updating Memory Threshold");
					gamer.memoryThreshold = Double.valueOf(memoryThresholdField.getText());
				}
			} catch (Exception ex) {
				System.out.println("Error when updating configuration; Changes have not been saved.");
				ex.printStackTrace();
			}

        }

    }

    @Override
    public void stateMachineMetaGame(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
    {
        // Sample gamers do no metagaming at the beginning of the match.
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    // This is the default State Machine
    @Override
    public StateMachine getInitialStateMachine() {
        return new CachedStateMachine(new ProverStateMachine());
    }

    // This is the defaul Sample Panel
    @Override
    public DetailPanel getDetailPanel() {
        return new SimpleDetailPanel();
    }

    @Override
    public ConfigPanel getConfigPanel() {
        return new MCTSConfigPanel(this);
    }

    @Override
    public void stateMachineStop() {
        gameRoles = null;
        roleIndices = null;
        opponent = null;
		nodeMap = null;
    }

    @Override
    public void stateMachineAbort() {
        gameRoles = null;
        roleIndices = null;
        opponent = null;
		nodeMap = null;
    }

    @Override
    public void preview(Game g, long timeout) throws GamePreviewException {
        // Sample gamers do no game previewing.
    }

    private Move getRandomMove() throws MoveDefinitionException {
        List<Move> moves = getStateMachine().getLegalMoves(getCurrentState(), getRole());
		return (moves.get(new Random().nextInt(moves.size())));
    }

	private double utility(MCTSNode node, boolean inverse) {
		double averageScore = node.totalScore / node.visits;
		if (inverse)
			averageScore *= -1;
		return averageScore + 40*Math.sqrt(2*Math.log(node.parent.visits)/node.visits);
	}

	private double oUtility(MCTSNode node) {
		double averageScore = node.opponentScore / node.visits;
		return averageScore + 40*Math.sqrt(2*Math.log(node.parent.visits)/node.visits);
	}

	private MCTSNode select(MCTSNode root) {

		MCTSNode current = root;

		while (true) {

			if (current.visits == 0 || getStateMachine().isTerminal(current.state))
				return expandNewNodes?current:(current.parent==null?current:current.parent);

			for (Map.Entry<Move, ArrayList<MCTSNode>> siblings : current.children.entrySet()) {
				for (MCTSNode child : siblings.getValue()) {
					if (child.visits == 0)
						return expandNewNodes?child:current;
				}
			}

			double score = Double.NEGATIVE_INFINITY;
			MCTSNode bestChild = current;

			for (Map.Entry<Move, ArrayList<MCTSNode>> siblings : current.children.entrySet()) { 
				MCTSNode bestSibling = null;
				double   siblingScore = Double.NEGATIVE_INFINITY;
				double   siblingOScore = Double.NEGATIVE_INFINITY;

				for (MCTSNode child : siblings.getValue()) {
					double newScore = Double.NEGATIVE_INFINITY;
					if (moveSelection == 0) { 
						newScore = utility(child, true);
						if (newScore > siblingScore) {
							siblingScore = utility(child, false);
							bestSibling = child;
							//assert child.parent == current;
							// A node may have multiple parents - visit the right one
							// when back propagating.
							child.parent = current;
							child.parentMove = siblings.getKey();
						}
					} else if (moveSelection == 1) {
						newScore = oUtility(child);
						if (newScore > siblingOScore) {
							siblingOScore = newScore;
							siblingScore = utility(child, false);
							bestSibling = child;
							//assert child.parent == current;
							// A node may have multiple parents - visit the right one
							// when back propagating.
							child.parent = current;
							child.parentMove = siblings.getKey();
						}
					}

				}

				if (siblingScore > score) {
					score = siblingScore;
					bestChild = bestSibling;
				}
			}

			assert bestChild != current;
			current = bestChild;
		}
	}

	private void expand(MCTSNode node) throws MoveDefinitionException, TransitionDefinitionException,
			GoalDefinitionException {

		//log("************************EXPAND************************\n");
		//log("Expanding Node: "+node.toString());

		if (getStateMachine().isTerminal(node.state))
			return;

		Map<Move, List<MachineState>> nextStates = getStateMachine().getNextStates(node.state, getRole());

		for (Map.Entry<Move,List<MachineState>> entry: nextStates.entrySet()) {
			MCTSNode child         = null;
			MachineState selection = null;
			Move 	 parentMove    = entry.getKey();

			for (MachineState s : entry.getValue()) {
				child = nodeMap.get(s);
				if (child == null) {
					child = new MCTSNode(s,node,parentMove);
					nodeMap.put(child.state, child);
				} else {
					child.parent = node;
					child.parentMove = parentMove;
				}
				node.addChild(child);
				//log("Adding new child: "+child.toString());
			}
		}

		//log("**********************************************************\n");
	}

	private void backpropagate(MCTSNode leaf, double [] scores) {

		MCTSNode current = leaf;
		double   currentScore = 0.0;
		double 	 currentOScore = 0.0;

		int pIndex = roleIndices.get(getRole());
		int oIndex = 0;
		if (opponent != null) {
			oIndex = roleIndices.get(opponent);
		}

		while (current != null) {
			current.visits++;

			current.totalScore += scores[pIndex];
			
			if (opponent != null) {
				current.opponentScore += scores[oIndex];
			}

			current = current.parent;

			//if (opponent != null) {
				//if (current != null) {
					//double bestOScore = currentOScore;
					//double bestScore  = currentScore;

					//for (MCTSNode child : current.children) {
						//double utility = child.totalScore/child.visits;
						//double oUtility = child.opponentScore/child.visits;

						//if (moveSelection == 0) {
							//if (child.parentMove == parentMove && child.visits > 5 && utility < currentScore) {
								//scores[pIndex] = utility;
								//scores[oIndex] = oUtility;
							//}
						//} else if (moveSelection == 1) {
							//if (child.parentMove == parentMove && child.visits > 5 && oUtility > bestOScore) {
								//scores[pIndex] = utility;
								//scores[oIndex] = oUtility;
								//bestOScore = oUtility;
							//}
						//}
					//}
				//}
			//}
		}
	}

    private Move getMove() throws TransitionDefinitionException, MoveDefinitionException,
                          GoalDefinitionException{
        if (gameRoles == null)
            gameRoles = getStateMachine().getRoles();

        if (roleIndices == null)
            roleIndices = getStateMachine().getRoleIndices();

        if (opponent == null) {
            opponent = getOpponent();
        }

		if (nodeMap == null) {
			nodeMap = new HashMap<MachineState, MCTSNode>();
		}

		numExpansions = 0;

		List<Move> moves = getStateMachine().getLegalMoves(getCurrentState(),getRole());
		if (moves.size() == 1)
			return moves.get(0);

		MCTSNode currentNode = nodeMap.get(getCurrentState());
		if (currentNode == null) {
			currentNode = new MCTSNode(getCurrentState(), null, null);
			nodeMap.put(getCurrentState(), currentNode);
		}
		currentNode.parent = null;
		currentNode.parentMove = null;

		double usedPercent=(double)(Runtime.getRuntime().totalMemory()
				-Runtime.getRuntime().freeMemory())*100.0/Runtime.getRuntime().maxMemory();

		System.out.println("Used Memory: "+usedPercent+"%");
		System.out.println("MEMORY THRESHOLD: "+memoryThreshold);
		expandNewNodes = true;

		if (usedPercent >= memoryThreshold) {
			System.out.println("Memory threshold exceeded - new states will not be expanded");
			expandNewNodes = false;
		}


		while (true) {
			MCTSNode selection = select(currentNode);

			if (expandNewNodes)
				expand(selection);

			List<Integer> scoresList = depthCharge(selection.state);

			double [] scores = new double[scoresList.size()];
			for (int i=0; i<scoresList.size(); i++)
				scores[i] = scoresList.get(i);

			backpropagate(selection, scores);

			numExpansions++;
		}

    }

	private Move getBestMove() throws MoveDefinitionException {

		MCTSNode current = nodeMap.get(getCurrentState());

		Move   bestMove  = null;
		double score = Double.NEGATIVE_INFINITY;

		log("**********************Best Move******************************\n");
		GamerLogger.log("MCTSGamer", "Current State: "+current.state+"\n");
		GamerLogger.log("MCTSGamer", "Children:\n");
		for (Map.Entry<Move, ArrayList<MCTSNode>> siblings : current.children.entrySet()) { 
			double   siblingScore = Double.POSITIVE_INFINITY;
			double   siblingOScore = Double.NEGATIVE_INFINITY;
			Move 	 currentMove = siblings.getKey();

			for (MCTSNode child : siblings.getValue()) {
				GamerLogger.log("MCTSGamer", child.toString());
				double newScore = child.totalScore/child.visits;
				double oScore   = 0.0;

				if (opponent != null) {
					oScore = child.opponentScore / child.visits;
				}

				if (moveSelection == 0) {
					if (newScore < siblingScore) {
						siblingScore = newScore;
					}
				} else if(moveSelection == 1) {
					if (oScore > siblingOScore) {
						siblingOScore = oScore;
						siblingScore = newScore;
					}
				}
			}

			if (siblingScore > score) {
				score = siblingScore;
				bestMove = currentMove;
			}
		}

		log("***********************************************************************\n");

		if (bestMove != null)
			return bestMove;
		else {
			System.out.println("No good move found, choosing randomly");
			return getRandomMove();
		}

	}

    /*
     * This function is called at the start of each round
     * You are required to return the Move your player will play
     * before the timeout.
     *
     */
    @Override
    public Move stateMachineSelectMove(long timeout)
        throws TransitionDefinitionException, MoveDefinitionException,
                          GoalDefinitionException
    {

        // We get the current start time
        long start = System.currentTimeMillis();

        bestMoveSoFar = null;
        System.out.println("TIMEOUT: "+(timeout-start));

        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<Move> f = exec.submit(new Callable<Move>() {
            public Move call() throws TransitionDefinitionException, MoveDefinitionException,
                          GoalDefinitionException {
                return getMove();
            }
        });

        Move selection = null;

        try {
            selection = f.get(timeout - start - timeout_buffer, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.out.println("getMove() timed-out");
            exec.shutdownNow();

			selection = getBestMove();
        } catch (Exception e) {
            System.out.println("getMove() was interrupted");
		    GamerLogger.logStackTrace("GamePlayer", e);
            selection = getRandomMove();
        }

		
		log("Num Expansions: "+numExpansions);
		log("Size of Node-map: "+nodeMap.size());
		//nodeMap = null;
        // We get the end time
        // It is mandatory that stop<timeout
        long stop = System.currentTimeMillis();

        /**
         * These are functions used by other parts of the GGP codebase
         * You shouldn't worry about them, just make sure that you have
         * moves, selection, stop and start defined in the same way as
         * this example, and copy-paste these two lines in your player
         */
        notifyObservers(new GamerSelectedMoveEvent(
                    getStateMachine().getLegalMoves(getCurrentState(), getRole()), selection, stop - start));
        return selection;
    }

    // TODO: Extend this for games with >2 players?
    private Role getOpponent() {
        for (Role r : gameRoles) {
            if (!r.equals(getRole()))
                return r;
        }

        return null;
    }

	private List<Integer> depthCharge(MachineState s) {
		try {
			MachineState fs = getStateMachine().performDepthCharge(s,null);
			return getStateMachine().getGoals(fs);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

}
