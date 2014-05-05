package org.ggp.base.player.gamer.statemachine.montecarlo;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import java.util.Random;

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

import org.ggp.base.util.logging.GamerLogger;

public class MCGamer extends StateMachineGamer
{
    private int depth_limit = 1;
    private List<Role> gameRoles = null;
    private Map<Role, Integer> roleIndices = null;
    private Role opponent = null;

    private Move bestMoveSoFar = null;
    
    private long timeout_buffer = 500; // In milliseconds

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

    private Move getRandomMove() throws TransitionDefinitionException, MoveDefinitionException,
                          GoalDefinitionException {
        List<Move> moves = getStateMachine().getLegalMoves(getCurrentState(), getRole());
		return (moves.get(new Random().nextInt(moves.size())));
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


        /**
         * We put in memory the list of legal moves from the
         * current state. The goal of every stateMachineSelectMove()
         * is to return one of these moves. The choice of which
         * Move to play is the goal of GGP.
         */
        List<Move> moves =
            getStateMachine().getLegalMoves(getCurrentState(), getRole());
        double max = Double.MIN_VALUE;
        Move selection = null;
        for (Move prospect: moves) {

            double score = minScore(getCurrentState(), prospect, Double.MIN_VALUE, Double.MAX_VALUE, 0);

            if (score >= max) {
                max = score;
                selection = prospect;
                bestMoveSoFar = prospect;
            }
        }

        System.out.println("MAX SCORE: "+max);

        if (max == 0) {
            return getRandomMove();
        }

        return selection;
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
            //selection = getRandomMove();
            if (bestMoveSoFar == null) {
                selection = getRandomMove();
            } else {
                selection = bestMoveSoFar;
            }
            bestMoveSoFar = null;
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

	private double montecarlo(MachineState s, int numProbes) {
		double total = 0.0;

		for (int probe = 0; probe < numProbes; probe++) {
			total = total + depthCharge(s);
		}

		return total/(double)numProbes;
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

    private double maxScore(MachineState state, double alpha, double beta, int level)
        throws TransitionDefinitionException, MoveDefinitionException,
                          GoalDefinitionException
    {
        if (getStateMachine().isTerminal(state)) {
            return getStateMachine().getGoal(state, getRole());
        }

        if (level >= depth_limit) {
			return montecarlo(state, 20);
        }

        List<Move> moves = getStateMachine().getLegalMoves(state, getRole());
        for (Move prospect : moves) {

            double score = minScore(state, prospect, alpha, beta, level);

            alpha = Math.max(alpha, score);

            if (alpha >= beta) {
                return beta;
            }
        }

        return alpha;
    }

    private double minScore(MachineState state, Move playerAction, double alpha, double beta, int level) 
        throws TransitionDefinitionException, MoveDefinitionException,
                          GoalDefinitionException
    {

        ArrayList<Move> curMoves = new ArrayList<Move>(gameRoles.size());
        curMoves.add(roleIndices.get(getRole()), playerAction);

        if (opponent == null) {
            return maxScore(getStateMachine().getNextState(state, curMoves), alpha, beta, level+1);
        }

        List<List<Move>> moves = getStateMachine().getLegalJointMoves(state, getRole(), playerAction);

        for (List<Move> prospect : moves) {
            double score =
              maxScore(getStateMachine().getNextState(state, prospect), alpha, beta, level+1);

            beta = Math.min(beta, score);

            if (beta <= alpha) {
                return alpha;
            }
        }

        return beta;
    }
}
