package org.ggp.base.util.statemachine.implementation.propnet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlRelation;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.propnet.factory.PropNetFactory;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.query.ProverQueryBuilder;

import org.ggp.base.util.propnet.architecture.components.And;
import org.ggp.base.util.propnet.architecture.components.Or;
import org.ggp.base.util.propnet.architecture.components.Not;
import org.ggp.base.util.propnet.architecture.components.Transition;
import org.ggp.base.util.propnet.architecture.components.Constant;

@SuppressWarnings("unused")
public class PropNetStateMachine extends StateMachine {
	/** The underlying proposition network  */
	private PropNet propNet;
	/** The topological ordering of the propositions */
	private List<Proposition> ordering;
	/** The player roles */
	private List<Role> roles;

	private void markBases(MachineState s) {
		Map<GdlSentence, Proposition> baseProps = propNet.getBasePropositions();

		for (Map.Entry<GdlSentence, Proposition> entry : baseProps.entrySet()) {
			entry.getValue().setValue(false);
		}

		for (GdlSentence sent : s.getContents()) {
			Proposition bp = baseProps.get(sent);
			if (bp != null)
				bp.setValue(true);
		}

        Set<Proposition> derivedBaseProps = new HashSet<Proposition>(baseProps.values());
	}

	private void markActions(List<Move> moves) {
		Map<GdlSentence, Proposition> inputProps = propNet.getInputPropositions();

		for (Map.Entry<GdlSentence, Proposition> entry : inputProps.entrySet()) {
			entry.getValue().setValue(false);
		}

		for (Move m : moves) {
			Proposition ip = inputProps.get(m.getContents());
			if (ip != null)
				ip.setValue(true);
		}
	}

    /* assumes change to state, i.e. when updating markings of the propnet */
    private void forwardPropagate() {
        for (Component c : ordering) {
            Set<Component> inputs = c.getInputs();

            boolean newComponentValue = true;

            for (Component input : inputs) {
                boolean cValue = false;

                if (input instanceof Or) {
                    for (Component cInput : input.getInputs()) {
                        cValue |= cInput.getValue();
                    }
                }
                else if (input instanceof And) {
                    cValue = true;
                    for (Component cInput : input.getInputs()) {
                        cValue &= cInput.getValue();
                    }
                }
                else if (input instanceof Not) {
                    cValue = !input.getSingleInput().getValue();
                }
                else if (input instanceof Transition) {
                    cValue = input.getSingleInput().getValue();
                }

                if (!cValue) {
                    newComponentValue = false;
                    break;
                }
            }
            Proposition p = (Proposition) c;
            p.setValue(newComponentValue);

            if (p.getValue())
                System.out.println(p.hashCode() + " has been set to true");
        }
    }

	/**
	 * Initializes the PropNetStateMachine. You should compute the topological
	 * ordering here. Additionally you may compute the initial state here, at
	 * your discretion.
	 */
	@Override
	public void initialize(List<Gdl> description) {
		propNet = PropNetFactory.create(description);
		roles = propNet.getRoles();
		ordering = getOrdering();
	}

	/**
	 * Computes if the state is terminal. Should return the value
	 * of the terminal proposition for the state.
	 */
	@Override
	public boolean isTerminal(MachineState state) {
		markBases(state);
		return propNet.getTerminalProposition().getValue();
	}

	/**
	 * Computes the goal for a role in the current state.
	 * Should return the value of the goal proposition that
	 * is true for that role. If there is not exactly one goal
	 * proposition true for that role, then you should throw a
	 * GoalDefinitionException because the goal is ill-defined.
	 */
	@Override
	public int getGoal(MachineState state, Role role)
	throws GoalDefinitionException {
		markBases(state);
		Set<Proposition> goalProps = propNet.getGoalPropositions().get(role);

		Proposition goalProposition = null;
		for (Proposition gp : goalProps) {
			if (gp.getValue()) {
				if (goalProposition != null)
					throw new GoalDefinitionException(state, role);

				goalProposition = gp;
			}

		}

		if (goalProposition == null)
			throw new GoalDefinitionException(state, role);

		return getGoalValue(goalProposition);
	}

	/**
	 * Returns the initial state. The initial state can be computed
	 * by only setting the truth value of the INIT proposition to true,
	 * and then computing the resulting state.
	 */
	@Override
	public MachineState getInitialState() {
		Proposition init = propNet.getInitProposition();
		init.setValue(true);
		return getStateFromBase();
	}

	/**
	 * Computes the legal moves for role in state.
	 */
	@Override
	public List<Move> getLegalMoves(MachineState state, Role role)
	throws MoveDefinitionException {
		markBases(state);

		ArrayList<Move> legalMoves = new ArrayList<Move>();

		Set<Proposition> legals = propNet.getLegalPropositions().get(role);

		for (Proposition lp : legals) {
			if (lp.getValue()) {
				legalMoves.add(getMoveFromProposition(lp));
			}
		}
		return legalMoves;
	}

	/**
	 * Computes the next state given state and the list of moves.
	 */
	@Override
	public MachineState getNextState(MachineState state, List<Move> moves)
	throws TransitionDefinitionException {
		markActions(moves);
		markBases(state);
        forwardPropagate();
		return getStateFromBase();
	}

	/**
	 * This should compute the topological ordering of propositions.
	 * Each component is either a proposition, logical gate, or transition.
	 * Logical gates and transitions only have propositions as inputs.
	 *
	 * The base propositions and input propositions should always be exempt
	 * from this ordering.
	 *
	 * The base propositions values are set from the MachineState that
	 * operations are performed on and the input propositions are set from
	 * the Moves that operations are performed on as well (if any).
	 *
	 * @return The order in which the truth values of propositions need to be set.
	 */
	public List<Proposition> getOrdering()
	{
		// List to contain the topological ordering.
		List<Proposition> order = new LinkedList<Proposition>();

		// All of the components in the PropNet
		List<Component> components = new ArrayList<Component>(propNet.getComponents());

		// All of the propositions in the PropNet.
		List<Proposition> propositions = new ArrayList<Proposition>(propNet.getPropositions());

        Set<Component> candidates = new HashSet<Component>();
        Set<Component> seen = new HashSet<Component>();
        Set<Proposition> inputs = new HashSet<Proposition>(propNet.getInputPropositions().values());

        // Initialize by finding components that are inputs
        for (Component c : components) {
        	if (c instanceof Proposition && inputs.contains((Proposition) c)) {
        		candidates.add(c);
        	}
        }

        // Breadth-first traversal through inputs
        while (candidates.size() != 0) {
            Set<Component> candidatesDownstream = new HashSet<Component>();
            // Go over all possible next candidates
            for (Component c : candidates) {
                for (Component out : c.getOutputs()) {
                    if (out.getOutputs().size() > 0 && !seen.contains(out)) {
                        candidatesDownstream.add(out);
                    }
                    if (out instanceof Proposition) {
                       order.add((Proposition) out);
                    }
                    seen.add(out);
                }
            }
            candidates.clear();
            candidates.addAll(candidatesDownstream);
        }

		return order;
	}

	/* Already implemented for you */
	@Override
	public List<Role> getRoles() {
		return roles;
	}

	/* Helper methods */

	/**
	 * The Input propositions are indexed by (does ?player ?action).
	 *
	 * This translates a list of Moves (backed by a sentence that is simply ?action)
	 * into GdlSentences that can be used to get Propositions from inputPropositions.
	 * and accordingly set their values etc.  This is a naive implementation when coupled with
	 * setting input values, feel free to change this for a more efficient implementation.
	 *
	 * @param moves
	 * @return
	 */
	private List<GdlSentence> toDoes(List<Move> moves)
	{
		List<GdlSentence> doeses = new ArrayList<GdlSentence>(moves.size());
		Map<Role, Integer> roleIndices = getRoleIndices();

		for (int i = 0; i < roles.size(); i++)
		{
			int index = roleIndices.get(roles.get(i));
			doeses.add(ProverQueryBuilder.toDoes(roles.get(i), moves.get(index)));
		}
		return doeses;
	}

	/**
	 * Takes in a Legal Proposition and returns the appropriate corresponding Move
	 * @param p
	 * @return a PropNetMove
	 */
	public static Move getMoveFromProposition(Proposition p)
	{
		return new Move(p.getName().get(1));
	}

	/**
	 * Helper method for parsing the value of a goal proposition
	 * @param goalProposition
	 * @return the integer value of the goal proposition
	 */
	private int getGoalValue(Proposition goalProposition)
	{
		GdlRelation relation = (GdlRelation) goalProposition.getName();
		GdlConstant constant = (GdlConstant) relation.get(1);
		return Integer.parseInt(constant.toString());
	}

	/**
	 * A Naive implementation that computes a PropNetMachineState
	 * from the true BasePropositions.  This is correct but slower than more advanced implementations
	 * You need not use this method!
	 * @return PropNetMachineState
	 */
	public MachineState getStateFromBase()
	{
		Set<GdlSentence> contents = new HashSet<GdlSentence>();
		for (Proposition p : propNet.getBasePropositions().values())
		{
			p.setValue(p.getSingleInput().getValue());
			if (p.getValue())
			{
				contents.add(p.getName());
			}

		}
		return new MachineState(contents);
	}
}
