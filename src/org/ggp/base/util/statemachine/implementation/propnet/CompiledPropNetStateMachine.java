package org.ggp.base.util.statemachine.implementation.propnet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.propnet.architecture.CompiledPropNet;
import org.ggp.base.util.propnet.factory.CompiledPropNetFactory;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.PropNetMachineState;
import org.ggp.base.util.statemachine.PropNetMove;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;


public class CompiledPropNetStateMachine extends StateMachine {
	/** The underlying proposition network  */
	private CompiledPropNet propNet;

	/** The player roles */
	private List<Role> roles;

	private MachineState currentState = null;

	/**
	 * Initializes the PropNetStateMachine. You should compute the topological
	 * ordering here. Additionally you may compute the initial state here, at
	 * your discretion.
	 */
	@Override
	public void initialize(String gameName, List<Gdl> description) {

		currentState = null;
		roles = null;
		propNet = null;

		try {
			propNet = CompiledPropNetFactory.create(gameName, description,true);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		roles = propNet.getRoles();

		System.out.println("Compiled PropNet StateMachine Initialized Successfully");

	}

	/**
	 * Computes if the state is terminal. Should return the value
	 * of the terminal proposition for the state.
	 */
	@Override
	public boolean isTerminal(MachineState s) {

		PropNetMachineState state = (PropNetMachineState)s;

		if (!state.equals(currentState)) {
			propNet.clear();
			propNet.setBaseProps(state.getBaseProps());
			propNet.update();
			currentState = state;
		}

		return propNet.getProp(propNet.getTermProposition());
	}

	/**
	 * Computes the goal for a role in the current state.
	 * Should return the value of the goal proposition that
	 * is true for that role. If there is not exactly one goal
	 * proposition true for that role, then you should throw a
	 * GoalDefinitionException because the goal is ill-defined.
	 */
	@Override
	public int getGoal(MachineState s, Role role)
	throws GoalDefinitionException {

		PropNetMachineState state = (PropNetMachineState) s;

		if (!state.equals(currentState)) {
			propNet.clear();
			propNet.setBaseProps(state.getBaseProps());
			propNet.update();
			currentState = state;
		}

		List<Integer> goalProps = propNet.getGoalPropositions().get(role);
		Map<Integer,Integer> goalValues = propNet.getGoalValues();

		boolean foundGoal = false;

		int goal = 0;
		for (Integer gp : goalProps) {
			if (propNet.getProp(gp)) {
				if (foundGoal)
					throw new GoalDefinitionException(state, role);

				goal = goalValues.get(gp);
				foundGoal = true;
			}

		}

		if (!foundGoal)
			throw new GoalDefinitionException(state, role);

		return goal;
	}

	/**
	 * Returns the initial state. The initial state can be computed
	 * by only setting the truth value of the INIT proposition to true,
	 * and then computing the resulting state.
	 */
	@Override
	public MachineState getInitialState() {

		System.out.println("CompiledPropNetSM: Generating Initial State");

		propNet.clear();
		propNet.setTrue(propNet.getInitProposition());
		propNet.update();
		propNet.updateBases();
		propNet.update();

		currentState = new PropNetMachineState(propNet.getBaseProps());
		return currentState;
	}

	/**
	 * Computes the legal moves for role in state.
	 */
	@Override
	public List<Move> getLegalMoves(MachineState s, Role role)
	throws MoveDefinitionException {

		PropNetMachineState state = (PropNetMachineState)s;

		if (!state.equals(currentState)) {
			propNet.clear();
			propNet.setBaseProps(state.getBaseProps());
			propNet.update();
			currentState = state;
		}

		ArrayList<Move> legalMoves = new ArrayList<Move>();

		List<Integer> legals = propNet.getLegalProps().get(role);
		Map<Integer,Integer> legalInputMap = propNet.getLegalInputMap();
		Map<Integer, GdlTerm> legalToGdlMap = propNet.getLegalToGdlMap();

		for (Integer lp : legals) {
			if (propNet.getProp(lp))
				legalMoves.add(new PropNetMove(legalInputMap.get(lp),legalToGdlMap.get(lp)));
		}

		return legalMoves;
	}

	/**
	 * Computes the next state given state and the list of moves.
	 */
	@Override
	public MachineState getNextState(MachineState s, List<Move> moves)
	throws TransitionDefinitionException {

		PropNetMachineState state = (PropNetMachineState) s;

		propNet.clear();
		propNet.setBaseProps(state.getBaseProps());

		for (int i = 0; i < moves.size(); i++) {
			Move m = moves.get(i);
			if (m instanceof PropNetMove) {
				PropNetMove move = (PropNetMove) m;
				propNet.setTrue(move.getInputProp());
			} else {
				Role r = roles.get(i);
				int idx = propNet.getInputPropMap().get(r).get(m.getContents());
				propNet.setTrue(idx);
			}
		}

		propNet.update();
		propNet.updateBases();
		propNet.update();

		currentState = new PropNetMachineState(propNet.getBaseProps());
		return currentState;
	}

	/* Already implemented for you */
	@Override
	public List<Role> getRoles() {
		return roles;
	}
}
