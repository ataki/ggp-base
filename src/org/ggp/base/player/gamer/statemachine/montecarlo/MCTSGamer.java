package org.ggp.base.player.gamer.statemachine.montecarlo;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;

import org.ggp.base.apps.player.config.ConfigPanel;
import org.ggp.base.apps.player.detail.DetailPanel;
import org.ggp.base.apps.player.detail.SimpleDetailPanel;
import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.player.gamer.exception.GamePreviewException;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.cache.CachedStateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.CompiledPropNetStateMachine;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

public class MCTSGamer extends StateMachineGamer {
	private List<Role> gameRoles = null;
	private Map<Role, Integer> roleIndices = null;
	private Role opponent = null;

	private enum MoveSelection {
		PESSIMISTIC, MIXED, OPTIMISTIC
	};

	private enum SMSelection {
		PROVER, PROPNET
	};

	private Long timeoutBuffer = 2000L; // In milliseconds

	// This map allows us to keep the previous updates in memory when playing a
	// game.
	// There does not seem to be a significant boost due to this, but it does
	// not
	// cost us too much memory either.
	// private HashMap<MachineState, MCTSNode> nodeMap = null;
	private Cache<MachineState, MCTSNode> nodeMap = null;
	private static final int NODE_CACHE_SIZE = 2000000;
	private static final int NODE_CACHE_DURATION = 2; // In minutes;

	private MoveSelection moveSelector = MoveSelection.MIXED;

	// Under memory pressure, new nodes will not be expanded.
	private Double memoryThreshold = 80.0; // Percentage of used memory
	private boolean expandNewNodes = true;

	private Double explorationBias = 40.0;

	// Some statistics about the player
	private long numPrevExpansions = 0;
	private long numExpansions = 0;
	private long numMovesMade = 0;

	// Run MCTS in separate thread to handle timeouts
	private final ExecutorService threadExecutor = Executors
			.newSingleThreadExecutor();
	private boolean keepRunning = false;

	// Number of levels of the tree to dump
	private static final int TREE_DUMP_LIMIT = 3;

	private SMSelection SMToUse = SMSelection.PROVER;

	// Parallelize depth-charges
	private final ExecutorService chargeExecutor = Executors
			.newCachedThreadPool();
	private Integer numProbes = 4;

	private static final String MOVE_START = "*************** Move %3d ***************";
	private static final String MOVE_END = "*************** End %3d ****************";

	private MCTSConfigPanel cPanel = null;

	private boolean usePropNet = false;
	private boolean propNetReady = false;
	private StateMachine propNetSM = null;
	private Future<?> propNetTask = null;

	private static final double INHIBITOR_PENALTY = 0.7;

	private MCTSNode currentNode;

	private Future<Move> getMoveTask = null;

	private boolean useOpponentTurns = false;

	private class MCTSNode {
		MachineState state;
		long visits;
		double totalScore;
		double opponentScore;
		MCTSNode parent;

		// Maps each legal move for this player, from this state, to the
		// list of potential game-states generated by making that move.
		HashMap<Move, ArrayList<MCTSNode>> children;

		// This field is not essential, but helps during debug.
		Move parentMove;

		public MCTSNode(MachineState s, int v, double score, double oScore,
				MCTSNode p, Move m) {
			this.state = s;
			this.visits = v;
			this.totalScore = score;
			this.opponentScore = oScore;
			this.children = new HashMap<Move, ArrayList<MCTSNode>>();
			this.parent = p;
			this.parentMove = m;
		}

		public MCTSNode(MachineState s, MCTSNode p, Move m) {
			this(s, 0, 0.0, 0.0, p, m);
		}

		private int getNumChildren() {
			int count = 0;
			for (Map.Entry<Move, ArrayList<MCTSNode>> entry : children
					.entrySet()) {
				count += entry.getValue().size();
			}
			return count;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("State:" + state);
			sb.append(" Visits:" + visits);
			sb.append(" TotalScore:" + totalScore + " " + "oScore:"
					+ opponentScore);
			sb.append(" Num children:" + getNumChildren());
			sb.append(" Parent Move:" + parentMove);
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

		protected String toDot(int limit) {
			if (limit == 0)
				return "";

			StringBuilder sb = new StringBuilder();
			String stateStr = state.toString();

			if (stateStr.length() > 40) {
				stateStr = stateStr.substring(0, 40);
			}

			sb.append("\"@" + Integer.toHexString(hashCode())
					+ "\"[shape=box, label=\"" + stateStr + "\n" + "Visits: "
					+ visits + " Score: " + totalScore + " oScore: "
					+ opponentScore + "\"]; ");
			sb.append("\n");

			if (limit == 1)
				return sb.toString();

			for (Map.Entry<Move, ArrayList<MCTSNode>> siblings : children
					.entrySet()) {
				String mvString = siblings.getKey().toString();
				for (MCTSNode child : siblings.getValue()) {
					sb.append(child.toDot(limit - 1));
					sb.append("\"@" + Integer.toHexString(hashCode()) + "\"->"
							+ "\"@" + Integer.toHexString(child.hashCode())
							+ "\"[label=\"" + mvString + "\"," + "penwidth="
							+ ((double) child.visits / visits) * 10 + "]; ");
				}
			}

			return sb.toString();
		}

	}

	private void log(String s) {
		GamerLogger.log("MCTSGamer", s);
	}

	@SuppressWarnings("serial")
	private class MCTSConfigPanel extends ConfigPanel implements ActionListener {

		private final String[] mvSelectStrings = { "Pessimistic", "Mixed",
				"Optimistic" };
		private final String[] SMSelectStrings = { "Prover", "PropNet" };

		private final JTextField numProbesField;
		private final JTextField timeoutBufferField;
		private final JTextField memoryThresholdField;
		private final JTextField explorationBiasField;

		private final JComboBox<?> mvSelectField;

		private final JButton dumpTreeButton;

		private final JComboBox<?> SMSelectField;

		private final MCTSGamer gamer;

		public MCTSConfigPanel(MCTSGamer g) {
			super(new GridBagLayout());
			this.gamer = g;

			// TODO: Is there a more elegant way to add new configuration
			// options?
			numProbesField = new JTextField(gamer.numProbes.toString());
			timeoutBufferField = new JTextField(gamer.timeoutBuffer.toString());
			memoryThresholdField = new JTextField(
					gamer.memoryThreshold.toString());
			mvSelectField = new JComboBox<Object>(mvSelectStrings);
			mvSelectField.setSelectedIndex(gamer.moveSelector.ordinal());
			explorationBiasField = new JTextField(
					gamer.explorationBias.toString());
			SMSelectField = new JComboBox<Object>(SMSelectStrings);
			SMSelectField.setSelectedIndex(gamer.SMToUse.ordinal());

			dumpTreeButton = new JButton("Dump Current Tree");

			numProbesField.addActionListener(this);
			timeoutBufferField.addActionListener(this);
			memoryThresholdField.addActionListener(this);
			mvSelectField.addActionListener(this);
			explorationBiasField.addActionListener(this);
			dumpTreeButton.addActionListener(this);
			SMSelectField.addActionListener(this);

			this.add(new JLabel("Number of probes:"), new GridBagConstraints(0,
					0, 1, 1, 0.0, 0.0, GridBagConstraints.EAST,
					GridBagConstraints.NONE, new Insets(20, 5, 5, 5), 5, 5));
			this.add(numProbesField, new GridBagConstraints(1, 0, 1, 1, 1.0,
					0.0, GridBagConstraints.CENTER,
					GridBagConstraints.HORIZONTAL, new Insets(20, 5, 5, 5), 5,
					5));

			this.add(new JLabel("Timeout Buffer:"), new GridBagConstraints(0,
					1, 1, 1, 0.0, 0.0, GridBagConstraints.EAST,
					GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
			this.add(timeoutBufferField, new GridBagConstraints(1, 1, 1, 1,
					1.0, 0.0, GridBagConstraints.CENTER,
					GridBagConstraints.BOTH, new Insets(5, 5, 5, 5), 5, 5));

			this.add(new JLabel("Memory Threshold(%):"),
					new GridBagConstraints(0, 2, 1, 1, 0.0, 0.0,
							GridBagConstraints.EAST, GridBagConstraints.NONE,
							new Insets(5, 5, 5, 5), 5, 5));
			this.add(memoryThresholdField, new GridBagConstraints(1, 2, 1, 1,
					1.0, 0.0, GridBagConstraints.CENTER,
					GridBagConstraints.BOTH, new Insets(5, 5, 5, 5), 5, 5));

			this.add(new JLabel("Move Selection:"), new GridBagConstraints(0,
					3, 1, 1, 0.0, 0.0, GridBagConstraints.EAST,
					GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
			this.add(mvSelectField, new GridBagConstraints(1, 3, 1, 1, 1.0,
					0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
					new Insets(5, 5, 5, 5), 5, 5));

			this.add(new JLabel("Exploration Bias:"), new GridBagConstraints(0,
					4, 1, 1, 0.0, 0.0, GridBagConstraints.EAST,
					GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
			this.add(explorationBiasField, new GridBagConstraints(1, 4, 1, 1,
					1.0, 0.0, GridBagConstraints.CENTER,
					GridBagConstraints.BOTH, new Insets(5, 5, 5, 5), 5, 5));

			this.add(new JLabel("State Machine:"), new GridBagConstraints(0, 5,
					1, 1, 0.0, 0.0, GridBagConstraints.EAST,
					GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
			this.add(SMSelectField, new GridBagConstraints(1, 5, 1, 1, 1.0,
					0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
					new Insets(5, 5, 5, 5), 5, 5));

			this.add(dumpTreeButton, new GridBagConstraints(0, 6, 0, 1, 0.0,
					0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
					new Insets(5, 5, 5, 5), 5, 5));

		}

		@Override
		public void actionPerformed(ActionEvent e) {

			try {
				if (e.getSource() == numProbesField) {
					gamer.numProbes = Integer.valueOf(numProbesField.getText());
					if (gamer.SMToUse == SMSelection.PROPNET) {
						gamer.numProbes = 1;
						numProbesField.setText(gamer.numProbes.toString());
					}
				} else if (e.getSource() == timeoutBufferField) {
					gamer.timeoutBuffer = Long.valueOf(timeoutBufferField
							.getText());
				} else if (e.getSource() == memoryThresholdField) {
					System.out.println("Updating Memory Threshold");
					gamer.memoryThreshold = Double.valueOf(memoryThresholdField
							.getText());
				} else if (e.getSource() == mvSelectField) {
					int selection = mvSelectField.getSelectedIndex();
					gamer.moveSelector = MoveSelection.values()[selection];
				} else if (e.getSource() == explorationBiasField) {
					gamer.explorationBias = Double.valueOf(explorationBiasField
							.getText());
				} else if (e.getSource() == SMSelectField) {
					int selection = SMSelectField.getSelectedIndex();
					gamer.SMToUse = SMSelection.values()[selection];
					if (gamer.SMToUse == SMSelection.PROPNET) {
						gamer.numProbes = 1;
						numProbesField.setText(gamer.numProbes.toString());
					}
				} else if (e.getSource() == dumpTreeButton) {
					dumpTreeButton.setEnabled(false);
					gamer.toDotFile("MCTSTree.dot");
					dumpTreeButton.setEnabled(true);
				}
			} catch (Exception ex) {
				System.err
						.println("Error when updating configuration; Changes have not been saved.");
				ex.printStackTrace();
			}

		}

		private void updateFields() {
			numProbesField.setText(gamer.numProbes.toString());
			timeoutBufferField.setText(gamer.timeoutBuffer.toString());
			memoryThresholdField.setText(gamer.memoryThreshold.toString());
			mvSelectField.setSelectedIndex(gamer.moveSelector.ordinal());
			explorationBiasField.setText(gamer.explorationBias.toString());
			SMSelectField.setSelectedIndex(gamer.SMToUse.ordinal());
		}

	}

	private Callable<List<Integer>> createDepthCharge(final MachineState m) {
		return new Callable<List<Integer>>() {
			@Override
			public List<Integer> call() {
				return depthCharge(m);
			}
		};
	}

	@Override
	public void stateMachineMetaGame(long timeout)
			throws TransitionDefinitionException, MoveDefinitionException,
			GoalDefinitionException {
		stateMachineMetaGame(timeout,null);
	}

	@Override
	public void stateMachineMetaGame(long timeout, final List<Gdl> rules)
			throws TransitionDefinitionException, MoveDefinitionException,
			GoalDefinitionException {

		if (usePropNet) {
			propNetTask = chargeExecutor.submit(new Runnable() {
				@Override
				public void run() {
					propNetSM = new CachedStateMachine(new CompiledPropNetStateMachine());
					propNetReady = true;
					try {
						propNetReady = propNetSM.initialize(null, rules);
					} catch (Exception e) {
						propNetReady = false;
					}
				}
			});

			long start = System.currentTimeMillis();
			boolean done = true;
			try {
				propNetTask.get(timeout-start-timeoutBuffer,TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				log("METAGAME: InterruptedException when running propNetTask");
				propNetReady = false;
				e.printStackTrace();
			} catch (ExecutionException e) {
				log("METAGAME: ExecutionException when running propNetTask");
				propNetReady = false;
				e.printStackTrace();
			} catch (TimeoutException e) {
				done = false;
				log("METAGAME: Timed out waiting for propnet to initialize.");
			} finally {
				if (done) {
					if (propNetReady) {
						log("METAGAME: PropNet Initialized successfully.");
						switchStateMachine(propNetSM);
					} else {
						log("METAGAME: Error Initalizing propNet - reverting to Prover");
					}
					propNetTask = null;
				}
			}
		}

		List<Role> roles = getStateMachine().getRoles();

		if (roles.size() > 2) {
			log("Detected game with multiple opponents - switching to pessimistic move selection");
			moveSelector = MoveSelection.PESSIMISTIC;
		}

		cPanel.updateFields();
	}

	@Override
	public String getName() {
		return getClass().getSimpleName();
	}

	@Override
	public StateMachine getInitialStateMachine() {

		if (cPanel == null)
			cPanel = new MCTSConfigPanel(this);

		if (nodeMap == null) {
			log("Creating new node-cache");
			nodeMap = CacheBuilder.newBuilder().maximumSize(NODE_CACHE_SIZE)
					.expireAfterAccess(NODE_CACHE_DURATION, TimeUnit.MINUTES)
					.weakValues().recordStats()
					.<MachineState, MCTSNode> build();
		}

		StateMachine SM = null;

		if (SMToUse == SMSelection.PROVER)
			SM = new ProverStateMachine();
		else if (SMToUse == SMSelection.PROPNET) {
			numProbes = 1; // TODO: PropNet is currently not thread-safe
			//SM = new CompiledPropNetStateMachine();
			SM = new ProverStateMachine();
			propNetReady = false;
			usePropNet = true;
			propNetSM = null;
		}

		cPanel.updateFields();

		return new CachedStateMachine(SM);
	}

	@Override
	public DetailPanel getDetailPanel() {
		return new SimpleDetailPanel();
	}

	@Override
	public ConfigPanel getConfigPanel() {
		if (cPanel == null)
			cPanel = new MCTSConfigPanel(this);

		return cPanel;
	}

	private void cleanup() {
		gameRoles = null;
		roleIndices = null;
		opponent = null;
		currentNode = null;
		keepRunning = false;
		propNetSM = null;
		usePropNet = false;
		propNetReady = false;

		if (propNetTask != null && !propNetTask.isDone()) {
			propNetTask.cancel(true);
		}
		propNetTask = null;

		if (getMoveTask != null && !getMoveTask.isDone()) {
			getMoveTask.cancel(true);
		}
		getMoveTask = null;

		if (nodeMap != null) {
			nodeMap.invalidateAll();
			nodeMap.cleanUp();
		}

		numExpansions = 0;
		numMovesMade = 0;

		System.gc();
	}

	@Override
	public void stateMachineStop() {
		cleanup();
	}

	@Override
	public void stateMachineAbort() {
		cleanup();
	}

	@Override
	public void preview(Game g, long timeout) throws GamePreviewException {
	}

	private Move getRandomMove() throws MoveDefinitionException {
		List<Move> moves = getStateMachine().getLegalMoves(getCurrentState(),
				getRole());
		return (moves.get(new Random().nextInt(moves.size())));
	}

	private double utility(MCTSNode node, boolean inverse) {
		double averageScore = node.totalScore / node.visits;
		if (inverse)
			averageScore *= -1;
		return averageScore + explorationBias
				* Math.sqrt(2 * Math.log(node.parent.visits) / node.visits);
	}

	private double oUtility(MCTSNode node) {
		double averageScore = node.opponentScore / node.visits;
		return averageScore + explorationBias
				* Math.sqrt(2 * Math.log(node.parent.visits) / node.visits);
	}

	private MCTSNode select(MCTSNode root) {

		MCTSNode current = root;
		int iteration = 0;
		while (true) {

			if (current.visits == 0
					|| getStateMachine().isTerminal(current.state)) {
				if (!expandNewNodes && iteration > 0)
					return current.parent;
				return current;
			}

			for (Map.Entry<Move, ArrayList<MCTSNode>> siblings : current.children
					.entrySet()) {
				for (MCTSNode child : siblings.getValue()) {
					if (child.visits == 0) {
						if (!expandNewNodes && iteration > 0)
							return current;

						return child;
					}
				}
			}

			double score = Double.NEGATIVE_INFINITY;
			MCTSNode bestChild = current;

			for (Map.Entry<Move, ArrayList<MCTSNode>> siblings : current.children
					.entrySet()) {
				MCTSNode bestSibling = null;
				double siblingScore = Double.NEGATIVE_INFINITY;
				double siblingOScore = Double.NEGATIVE_INFINITY;

				for (MCTSNode child : siblings.getValue()) {
					double newScore = Double.NEGATIVE_INFINITY;
					if (moveSelector == MoveSelection.PESSIMISTIC) {
						newScore = utility(child, true);
						if (newScore > siblingScore) {
							siblingScore = utility(child, false);
							bestSibling = child;
							// assert child.parent == current;
							// A node may have multiple parents - visit the
							// right one
							// when back propagating.
							child.parent = current;
							child.parentMove = siblings.getKey();
						}
					} else if (moveSelector == MoveSelection.MIXED) {
						newScore = oUtility(child);
						if (newScore > siblingOScore) {
							siblingOScore = newScore;
							siblingScore = utility(child, false);
							bestSibling = child;
							// assert child.parent == current;
							// A node may have multiple parents - visit the
							// right one
							// when back propagating.
							child.parent = current;
							child.parentMove = siblings.getKey();
						}
					} else if (moveSelector == MoveSelection.OPTIMISTIC) {
						newScore = utility(child, false);
						if (newScore > siblingScore) {
							siblingScore = newScore;
							bestSibling = child;
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
			iteration++;
		}
	}

	private void expand(MCTSNode node) throws MoveDefinitionException,
			TransitionDefinitionException, GoalDefinitionException {

		// log("************************EXPAND************************\n");
		// log("Expanding Node: "+node.toString());

		if (node.visits > 0 || getStateMachine().isTerminal(node.state))
			return;

		Map<Move, List<MachineState>> nextStates = getStateMachine()
				.getNextStates(node.state, getRole());

		for (Map.Entry<Move, List<MachineState>> entry : nextStates.entrySet()) {
			MCTSNode child = null;
			Move parentMove = entry.getKey();

			for (MachineState s : entry.getValue()) {
				child = nodeMap.getIfPresent(s);
				if (child == null) {
					child = new MCTSNode(s, node, parentMove);
					nodeMap.put(child.state, child);
				} else {
					child.parent = node;
					child.parentMove = parentMove;
				}
				node.addChild(child);
				// log("Adding new child: "+child.toString());
			}
		}

		if (getStateMachine().isGoalInhibitor(getRole(), node.state)){
			log("Inhibitor state found; Applying penalty");
			node.visits = (int) (INHIBITOR_PENALTY * numPrevExpansions);
		}

		// log("**********************************************************\n");
	}

	private void backpropagate(MCTSNode leaf, double[] scores) {

		MCTSNode current = leaf;

		int pIndex = roleIndices.get(getRole());
		int oIndex = 0;
		if (opponent != null) {
			oIndex = roleIndices.get(opponent);
		}

		while (current != null) {
			current.visits += numProbes;

			current.totalScore += scores[pIndex];

			if (opponent != null) {
				current.opponentScore += scores[oIndex];
			}

			current = current.parent;
		}
	}

	private Move getMove() throws TransitionDefinitionException,
			MoveDefinitionException, GoalDefinitionException {

		if (gameRoles == null)
			gameRoles = getStateMachine().getRoles();

		if (roleIndices == null)
			roleIndices = getStateMachine().getRoleIndices();

		if (opponent == null) {
			opponent = getOpponent();
		}

		numPrevExpansions = numExpansions;
		numExpansions = 0;

		currentNode = nodeMap.getIfPresent(getCurrentState());
		if (currentNode == null) {
			log("!!!!!!!!!!Could not find current node in node-map");
			currentNode = new MCTSNode(getCurrentState(), null, null);
			nodeMap.put(getCurrentState(), currentNode);
		}
		currentNode.parent = null;
		currentNode.parentMove = null;

//		List<Move> moves = getStateMachine().getLegalMoves(getCurrentState(),
//				getRole());
//		if (moves.size() == 1)
//			return moves.get(0);

		double usedPercent = (double) (Runtime.getRuntime().totalMemory() - Runtime
				.getRuntime().freeMemory())
				* 100.0
				/ Runtime.getRuntime().maxMemory();

		log(String.format("Used Memory: %.3f%%", usedPercent));

		expandNewNodes = true;
		if (usedPercent >= memoryThreshold) {
			log("Memory threshold exceeded - new states will not be expanded");
			expandNewNodes = false;
		}

		while (keepRunning) {
			MCTSNode selection = select(currentNode);

			if (Thread.currentThread().isInterrupted())
				return null;

			expand(selection);

			if (Thread.currentThread().isInterrupted())
				return null;

			double[] scores = runDepthCharge(selection.state);

			if (Thread.currentThread().isInterrupted())
				return null;

			backpropagate(selection, scores);

			numExpansions++;
		}

		return null;

	}

	private double[] runDepthCharge(MachineState s) {

		if (numProbes == 1) {
			List<Integer> chargeScores = depthCharge(s);
			double[] scores = new double[chargeScores.size()];
			for (int i = 0; i < scores.length; i++)
				scores[i] += chargeScores.get(i);
			return scores;
		}

		double[] scores = new double[getStateMachine().getRoles().size()];

		ArrayList<Callable<List<Integer>>> charges = new ArrayList<Callable<List<Integer>>>(
				numProbes);
		for (int i = 0; i < numProbes; i++) {
			charges.add(createDepthCharge(s));
		}

		List<Future<List<Integer>>> results = null;

		try {
			results = chargeExecutor.invokeAll(charges);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		for (Future<List<Integer>> result : results) {
			List<Integer> chargeScores = null;

			try {
				chargeScores = result.get();
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}

			for (int i = 0; i < scores.length; i++) {
				scores[i] += chargeScores.get(i);
			}
		}

		return scores;
	}

	private Move getBestMove() throws MoveDefinitionException {

		MCTSNode current = nodeMap.getIfPresent(getCurrentState());

		if (current == null) {
			log("BestMove: current node is null, returning random move!!!!!!!!!!!!!");
			return getRandomMove();
		}

		Move bestMove = null;
		double score = Double.NEGATIVE_INFINITY;

		log("Current State:");
		log(current.toString());
		for (Map.Entry<Move, ArrayList<MCTSNode>> siblings : current.children
				.entrySet()) {
			double siblingScore = Double.POSITIVE_INFINITY;

			if (moveSelector == MoveSelection.OPTIMISTIC)
				siblingScore = Double.NEGATIVE_INFINITY;

			double siblingOScore = Double.NEGATIVE_INFINITY;
			Move currentMove = siblings.getKey();

			for (MCTSNode child : siblings.getValue()) {
				// GamerLogger.log("MCTSGamer", child.toString());
				if (child.visits == 0)
					continue;

				double newScore = child.totalScore / child.visits;
				double oScore = 0.0;

				if (opponent != null) {
					oScore = child.opponentScore / child.visits;
				}

				if (moveSelector == MoveSelection.PESSIMISTIC) {
					if (newScore < siblingScore) {
						siblingScore = newScore;
					}
				} else if (moveSelector == MoveSelection.MIXED) {
					if (oScore > siblingOScore) {
						siblingOScore = oScore;
						siblingScore = newScore;
					}
				} else if (moveSelector == MoveSelection.OPTIMISTIC) {
					if (newScore > siblingScore) {
						siblingScore = newScore;
					}
				}
			}

			if (!Double.isInfinite(siblingScore) && siblingScore > score) {
				score = siblingScore;
				bestMove = currentMove;
			}
		}

		log("BEST SCORE: " + score);

		if (bestMove != null)
			return bestMove;
		else {
			log("No good move found, choosing randomly\n");
			return getRandomMove();
		}

	}

	/*
	 * This function is called at the start of each round You are required to
	 * return the Move your player will play before the timeout.
	 */
	@Override
	public Move stateMachineSelectMove(long timeout)
			throws TransitionDefinitionException, MoveDefinitionException,
			GoalDefinitionException {

		// We get the current start time
		long start = System.currentTimeMillis();
		Move selection = null;

		if (propNetTask != null && propNetTask.isDone()) {
			if (propNetReady) {
				log("MOVE: Switching to propNet...");
				switchStateMachine(propNetSM);
				log("MOVE: Invalidating node-map...");
				nodeMap.invalidateAll(); // TODO: PropNet states are not compatible with Prover states.
				propNetTask = null;
			} else {
				log("MOVE: PropNet creation failed, continuing to use Prover...");
				propNetTask = null;
			}
		}

		List<Move> legalMoves = getStateMachine().getLegalMoves(getCurrentState(), getRole());
		long buffer = timeoutBuffer;



		log(String.format(MOVE_START, numMovesMade));

		if (legalMoves.size() == 1) {
			if (useOpponentTurns) {
				buffer = 5000L;
			} else {
				log("Single legal move - skipping tree search.");
				System.gc();
				long stop = System.currentTimeMillis();
				log(String.format(MOVE_END, numMovesMade));
				numMovesMade++;
				selection = legalMoves.get(0);
				notifyObservers(new GamerSelectedMoveEvent(legalMoves, selection, stop
						- start));
				return selection;
			}
		}

		if (getMoveTask != null && !getMoveTask.isDone()) {
			log("!!!!!!!! PREVIOUS MOVE TASK STILL RUNNING !!!!!!!!!!!!");
		}

		keepRunning = true;
		getMoveTask = threadExecutor.submit(new Callable<Move>() {
			@Override
			public Move call() throws TransitionDefinitionException,
					MoveDefinitionException, GoalDefinitionException {
				return getMove();
			}
		});


		long timeStart = System.currentTimeMillis();
		log("TIMEOUT: " + (timeout - timeStart));
		long timeEnd = timeStart;
		try {
			selection = getMoveTask.get(timeout - timeStart - buffer,
					TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			timeEnd = System.currentTimeMillis();
			keepRunning = false;
			getMoveTask.cancel(true);
			if (legalMoves.size() == 1)
				selection = legalMoves.get(0);
			else
				selection = getBestMove();
		} catch (Exception e) {
			System.err.println("getMove() was interrupted");
			GamerLogger.logStackTrace("GamePlayer", e);
			selection = getRandomMove();
		}

		log("Num Expansions: " + numExpansions);
		log("Size of Node-map: " + nodeMap.size());
		log("Cache Stats:\n" + nodeMap.stats().toString());

		// We get the end time
		// It is mandatory that stop<timeout
		long stop = System.currentTimeMillis();
		log("Total: "+(stop-start)+" getMove(): "+(stop-timeStart)+" Timeout: "+(timeEnd - timeStart));
		log(String.format(MOVE_END, numMovesMade));
		numMovesMade++;
		/**
		 * These are functions used by other parts of the GGP codebase You
		 * shouldn't worry about them, just make sure that you have moves,
		 * selection, stop and start defined in the same way as this example,
		 * and copy-paste these two lines in your player
		 */
		notifyObservers(new GamerSelectedMoveEvent(legalMoves, selection, stop
				- start));
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
			MachineState fs = getStateMachine().performDepthCharge(s, null);
			return getStateMachine().getGoals(fs);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private String toDot() {
		StringBuilder sb = new StringBuilder();
		MCTSNode root = nodeMap.getIfPresent(getCurrentState());

		sb.append("digraph MCTSTree\n{\n");
		if (root != null)
			sb.append("\t" + root.toDot(TREE_DUMP_LIMIT) + "\n");
		sb.append("}");

		return sb.toString();
	}

	private void toDotFile(String filename) {
		if (numMovesMade == 0)
			return;
		try {
			File f = new File(filename);
			FileOutputStream fos = new FileOutputStream(f);
			OutputStreamWriter fout = new OutputStreamWriter(fos, "UTF-8");
			fout.write(toDot());
			fout.close();
			fos.close();
		} catch (Exception e) {
			GamerLogger.logStackTrace("MCTSGamer", e);
		}
	}

}
