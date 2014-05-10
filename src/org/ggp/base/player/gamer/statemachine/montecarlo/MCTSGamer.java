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

	private Integer moveSelection = 0;

	private class MCTSNode {
		MachineState state;
		int visits;
		double totalScore;
		ArrayList<MCTSNode> children;
		MCTSNode parent;
		Move 	 parentMove;

		public MCTSNode(MachineState s, int v, double score, MCTSNode p, Move m) {
			this.state = s;
			this.visits = v;
			this.totalScore = score;
			this.children = new ArrayList<MCTSNode>();
			this.parent = p;
			this.parentMove = m;
		}

		public MCTSNode(MachineState s, MCTSNode p, Move m) {
			this(s,0,0.0,p,m);
		}

	}

    @SuppressWarnings("serial")
    private class MCTSConfigPanel extends ConfigPanel implements ActionListener {

        private final JTextField numProbesField;
        private final JTextField timeoutBufferField;

        private final MCTSGamer gamer;

        public MCTSConfigPanel(MCTSGamer g) {
            super(new GridBagLayout());

            this.gamer = g;
            
            numProbesField = new JTextField(gamer.numProbes.toString());
            timeoutBufferField = new JTextField(gamer.timeout_buffer.toString());

            numProbesField.addActionListener(this);
            timeoutBufferField.addActionListener(this);

            this.add(new JLabel("Number of probes:"), new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0, GridBagConstraints.EAST, GridBagConstraints.NONE, new Insets(20, 5, 5, 5), 5, 5));
            this.add(numProbesField, new GridBagConstraints(1, 0, 1, 1, 1.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL, new Insets(20, 5, 5, 5), 5, 5));
            this.add(new JLabel("Timeout Buffer:"), new GridBagConstraints(0, 1, 1, 1, 0.0, 0.0, GridBagConstraints.EAST, GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
            this.add(timeoutBufferField, new GridBagConstraints(1, 1, 1, 1, 1.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH, new Insets(5, 5, 5, 5), 5, 5));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            
            if (e.getSource() == numProbesField) {
                gamer.numProbes = Integer.valueOf(numProbesField.getText());
            } else if (e.getSource() == timeoutBufferField) {
                gamer.timeout_buffer = Long.valueOf(timeoutBufferField.getText());
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

	private double utility(MCTSNode node) {
		double averageScore = node.totalScore / node.visits;
		return averageScore + Math.sqrt(2*Math.log(node.parent.visits)/node.visits);
	}

	private MCTSNode select(MCTSNode root) {

		MCTSNode current = root;

		while (true) {
			if (current.visits == 0 || getStateMachine().isTerminal(current.state))
				return current;

			for (MCTSNode child : current.children) {
				if (child.visits == 0)
					return child;
			}

			double score = 0.0;
			MCTSNode result = current;

			for (MCTSNode child : current.children) {
				double newScore = utility(child);
				if (newScore >= score) {
					score = newScore;
					result = child;
				}
			}

			assert result != current;
			current = result;
		}
	}

	private void expand(MCTSNode node) throws MoveDefinitionException, TransitionDefinitionException,
			GoalDefinitionException {

		if (getStateMachine().isTerminal(node.state))
			return;

		Map<Move, List<MachineState>> nextStates = getStateMachine().getNextStates(node.state, getRole());

		for (Map.Entry<Move,List<MachineState>> entry: nextStates.entrySet()) {
			MCTSNode child         = null;
			MachineState selection = null;
			Move 	 parentMove    = entry.getKey();
			for (MachineState s : entry.getValue()) {
				child = new MCTSNode(s,node,parentMove);
				node.children.add(child);
				nodeMap.put(child.state, child);
			}
		}
	}

	private void backpropagate(MCTSNode leaf, double score) {

		MCTSNode current = leaf;

		while (current != null) {
			Move parentMove = current.parentMove;
			current.visits++;
			current.totalScore += score;

			current = current.parent;

			if (current != null) {
				for (MCTSNode child : current.children) {
					double utility = child.totalScore/child.visits;
					if (child.parentMove == parentMove && utility < score)
						score = utility;
				}
			}
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

		while (true) {
			MCTSNode selection = select(currentNode);
			expand(selection);

			double score = depthCharge(selection.state);
			backpropagate(selection, score);
		}

    }

	private Move getBestMove() throws MoveDefinitionException {

		MCTSNode current = nodeMap.get(getCurrentState());

		double bestScore = 0.0;
		Move   bestMove  = null;

		List<Move> moves = getStateMachine().getLegalMoves(current.state,getRole());
		double[] scores = new double[moves.size()];

		for (int i=0; i<moves.size(); i++)
			scores[i] = Double.MAX_VALUE;

		for (MCTSNode child : current.children) {
			double score = child.totalScore/child.visits;
			int idx = moves.indexOf(child.parentMove);
			if (score < scores[idx]) {
				scores[idx] = score;
			}
		}

		System.out.println("SCORES: "+Arrays.toString(scores));
		for (int i=0; i<moves.size(); i++) {
			if (scores[i] >= bestScore) {
				bestScore = scores[i];
				bestMove = moves.get(i);
			}
		}

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

	private double depthCharge(MachineState s) {
		try {
			MachineState fs = getStateMachine().performDepthCharge(s,null);
			return getStateMachine().getGoal(fs, getRole());
		} catch (Exception e) {
			e.printStackTrace();
			return 0;
		}
	}

}
