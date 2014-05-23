package org.ggp.base.player.gamer.statemachine.sample;

import java.util.List;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.cache.CachedStateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.PropNetStateMachine;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;

/**
 * MyPropnetTester runs your state machine in parallel with
 * a ProverStateMachine. It plays a lot of random moves
 * and warn you as soon as a discrepancy appears
 *
 */
public final class MyPropNetTester extends SampleGamer
{
	@Override
	public void stateMachineMetaGame(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		System.out.println("Metagame. Preparing to test the state machine");

		StateMachine stateMachine = getStateMachine();
		ProverStateMachine psm = (ProverStateMachine)stateMachine;
		List gdlDescription = psm.gdlDescription;

		// The only line you have to adapt in this file
		StateMachine stateMachineX = new CachedStateMachine(new PropNetStateMachine());

		stateMachineX.initialize(gdlDescription);

		MachineState rootState = stateMachine.getInitialState();
		MachineState rootStateX = stateMachineX.getInitialState();
		if(!compare(rootState, rootStateX)){
			System.out.println("Initial states are different");
			System.out.println(rootState);
			System.out.println(rootStateX);
			return;
		}

		long finishBy = timeout - 1000;

		int nbExpansion = 0;
		boolean abort = false;

		while(System.currentTimeMillis() < finishBy && !abort){
			MachineState state = rootState;

			while(true){
				boolean isTerminal = stateMachine.isTerminal(state);
				boolean isTerminalX = stateMachineX.isTerminal(state);
				if(!compare(isTerminal, isTerminalX)){
					System.out.println("DISCREPANCY between isTerminal values");
					System.out.println("State : " + state);
					abort = true;
					break;
				}

				if(isTerminal){
					List goal = stateMachine.getGoals(state);
					List goalX = stateMachineX.getGoals(state);
					if(!compare(goal, goalX)){
						System.out.println("DISCREPANCY between goal values");
						System.out.println(goal);
						System.out.println(goalX);
						abort = true;
						break;
					}
					break;
				}

				for(Role role : stateMachine.getRoles()){
					List moves = stateMachine.getLegalMoves(state, role);
					List movesX = stateMachineX.getLegalMoves(state, role);
					if(!compare(moves, movesX, role)){
						System.out.println("DISCREPANCY between legal moves for role " + role);
						System.out.println(moves);
						System.out.println(movesX);
						abort = true;
						break;
					}
				}

				List jointMove = stateMachine.getRandomJointMove(state);


				MachineState nextState = stateMachine.getNextState(state, jointMove);
				MachineState nextStateX = stateMachineX.getNextState(state, jointMove);
				if(!compare(nextState, nextStateX)){
					System.out.println("DISCREPANCY between next states");
					System.out.println("Previous state : " + state);
					System.out.println("Joint move : " + jointMove);
					System.out.println("New state : " + nextState);
					System.out.println("New stateX : " + nextStateX);

					abort = true;
					break;
				}

				state = nextState;
				nbExpansion++;
			}
		}

		System.out.println("Metagaming finished");
		System.out.println("Nb expansion : " + nbExpansion);
	}

	/**
	 * Four helper functions
	 * A bit overkill
	 */
	public boolean compare(MachineState s, MachineState sX){
		if(!s.equals(sX)){
			return false;
		}
		return true;
	}

	public boolean compare(boolean b, boolean bX){
		if(b != bX){
			return false;
		}
		return true;
	}

	public boolean compare(List l, List lX){
		if(!l.equals(lX)){
			return false;
		}
		return true;
	}

	public boolean compare(List<Move> l, List<Move> lX, Role r){
		for(Move m : l){
			if(!lX.contains(m)){
				return false;
			}
		}
		for(Move m : lX){
			if(!l.contains(m)){
				return false;
			}
		}
		return true;
	}

	/**
	 * A legal gamer
	 */
	@Override
	public Move stateMachineSelectMove(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		long start = System.currentTimeMillis();

		List<Move> moves = getStateMachine().getLegalMoves(getCurrentState(), getRole());
		Move selection = moves.get(0);
		long stop = System.currentTimeMillis();

		notifyObservers(new GamerSelectedMoveEvent(moves, selection, stop - start));
		return selection;
	}

	@Override
	public StateMachine getInitialStateMachine() {
		return new ProverStateMachine();
	}
}