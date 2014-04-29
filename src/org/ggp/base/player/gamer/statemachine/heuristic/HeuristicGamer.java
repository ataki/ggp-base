package org.ggp.base.player.gamer.statemachine.heuristic;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import org.ggp.base.apps.player.detail.DetailPanel;
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

public class HeuristicGamer extends StateMachineGamer
{
  private int depth_limit = 3;
	private List<Role> gameRoles = null;
	private Map<Role, Integer> roleIndices = null;
	private Role opponent = null;

	@Override
	public void stateMachineMetaGame(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		// Sample gamers do no metagaming at the beginning of the match.
	}



	/** This will currently return "SampleGamer"
	 * If you are working on : public abstract class MyGamer extends SampleGamer
	 * Then this function would return "MyGamer"
	 */
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
	public void stateMachineStop() {
		gameRoles = null;
		roleIndices = null;
		opponent = null;
	}

	@Override
	public void stateMachineAbort() {
		gameRoles = null;
		roleIndices = null;
		opponent = null;
	}

	@Override
	public void preview(Game g, long timeout) throws GamePreviewException {
		// Sample gamers do no game previewing.
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

		if (gameRoles == null)
			gameRoles = getStateMachine().getRoles();

		if (roleIndices == null)
			roleIndices = getStateMachine().getRoleIndices();

		if (opponent == null) {
			opponent = getOpponent();
		}

		/**
		 * We put in memory the list of legal moves from the
		 * current state. The goal of every stateMachineSelectMove()
		 * is to return one of these moves. The choice of which
		 * Move to play is the goal of GGP.
		 */
		List<Move> moves =
			getStateMachine().getLegalMoves(getCurrentState(), getRole());
        int max = 0;
        Move selection = null;
        for (Move prospect: moves) {

            int score = minScore(getCurrentState(), prospect, 0);

            if (score > max) {
                max = score;
                selection = prospect;
            }
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
		notifyObservers(new GamerSelectedMoveEvent(moves, selection, stop - start));
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


    private int maxScore(MachineState state, int level)
		throws TransitionDefinitionException, MoveDefinitionException,
						  GoalDefinitionException
    {
        if (getStateMachine().isTerminal(state)) {
            return getStateMachine().getGoal(state, getRole());
        }

        if (level >= depth_limit) {
          return 0;
        }

        List<Move> moves = getStateMachine().getLegalMoves(state, getRole());
        ArrayList<Move> curMoves =
			new ArrayList<Move>(getStateMachine().getRoles().size());
        int max = Integer.MIN_VALUE;
        for (Move prospect : moves) {
            curMoves.add(roleIndices.get(getRole()), prospect);
            int score = minScore(state, prospect, level);

            if (score > max) {
                max = score;
            }
        }

        return max;
    }

	private int minScore(MachineState state, Move playerAction, int level) 
		throws TransitionDefinitionException, MoveDefinitionException,
						  GoalDefinitionException
    {

        List<Move> moves = getStateMachine().getLegalMoves(state, opponent);
        ArrayList<Move> curMoves = new ArrayList<Move>(gameRoles.size());
		curMoves.add(roleIndices.get(getRole()), playerAction);
        int min = Integer.MAX_VALUE;
        for (Move prospect : moves) {
            curMoves.add(roleIndices.get(opponent), prospect);
            int score =
              maxScore(getStateMachine().getNextState(state, curMoves), level+1);

            if (score < min) {
                min = score;
            }
        }

        return min;
    }
}
