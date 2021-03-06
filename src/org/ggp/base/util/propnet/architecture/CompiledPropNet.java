package org.ggp.base.util.propnet.architecture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlRelation;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.statemachine.Role;

public abstract class CompiledPropNet {

	/**
	 * Takes in a Legal Proposition and returns the appropriate corresponding Move
	 * @param p
	 * @return a PropNetMove
	 */
	public static GdlTerm getGdlTermFromProposition(Proposition p)
	{
		return p.getName().get(1);
	}
	protected boolean[] network;

	protected final Map<Proposition, Integer> indexMap;
	protected final List<Integer> baseProps;

	protected final int initProposition;

	protected final int termProposition;

	protected final List<Integer> inputProps;
	protected final Map<Role, List<Integer>> legalProps;

	protected final Map<Integer, Integer> legalInputMap;

	protected final List<Role> roles;

	protected final Map<Role, List<Integer>> goalPropositions;

	protected final Set<Integer> latches;

	protected final Map<Role, Set<Integer>> inhibitors;

	protected final Map<Integer, Integer> goalValues;
	protected final int numBases;

	protected final int numInputs;
	protected Map<Role,Map<GdlTerm,Integer>> inputPropMap;

	protected Map<Integer, GdlTerm> legalToGdlMap;

	protected final Map<Integer,List<Integer>> affectedProps;

	protected List<Integer> transitionalProps;
	protected List<Integer> transitionalPropOrdering;

	public List<Integer> getTransitionalProps() {
		return transitionalProps;
	}

	public List<Integer> getTransitionalPropOrdering() {
		return transitionalPropOrdering;
	}

	public Map<Integer, List<Integer>> getAffectedProps() {
		return affectedProps;
	}

	public CompiledPropNet(int size, Map<Proposition, Integer> indexMap, PropNet p) {
		network = new boolean[size];
		this.indexMap = indexMap;

		baseProps = recordBaseProps(p);
		initProposition = indexMap.get(p.getInitProposition());
		termProposition = indexMap.get(p.getTerminalProposition());
		legalProps = recordLegalInputs(p);
		legalInputMap = makeLegalInputMap(p);
		roles = p.getRoles();
		inputProps = recordInputProps(p);
		numBases = baseProps.size();
		numInputs = inputProps.size();
		goalPropositions = recordGoalPropositions(p);
		goalValues = recordGoalValues(p);
		latches = recordLatches(p);
		inhibitors = recordInhibitors(p);
		affectedProps = recordAffectedProps(p);

	}

	private Map<Integer, List<Integer>> recordAffectedProps(PropNet p) {
		Map<Integer,List<Integer>> affectedProps = new HashMap<Integer,List<Integer>>();

		for (Map.Entry<Proposition, List<Integer>> e : p.getDeltaIndices().entrySet()) {
			affectedProps.put(indexMap.get(e.getKey()), e.getValue());
		}

		transitionalPropOrdering = new LinkedList<Integer>();
		transitionalProps = new LinkedList<Integer>();
		for (Proposition prop : p.getTransitionalProps()) {
			transitionalProps.add(indexMap.get(prop));
		}

		for (Integer order : p.getTransitionalPropOrdering()) {
			transitionalPropOrdering.add(order);
		}

		return affectedProps;
	}

	public void clear() {
		network = new boolean[network.length];
	}

	public boolean [] getBaseProps() {
		return Arrays.copyOfRange(network, 0, baseProps.size());
	}

	public boolean [] getInputProps() {
		return Arrays.copyOfRange(network, numBases, numBases+numInputs);
	}

	public Map<Role, List<Integer>> getGoalPropositions() {
		return goalPropositions;
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

	public Map<Integer, Integer> getGoalValues() {
		return goalValues;
	}
	public Map<Role, Set<Integer>> getInhibitors() {
		return inhibitors;
	}

	public int getInitProposition() {
		return initProposition;
	}

	public Map<Role, Map<GdlTerm, Integer>> getInputPropMap() {
		return inputPropMap;
	}

	public Map<Integer, Integer> getLegalInputMap() {
		return legalInputMap;
	}

	public Map<Role, List<Integer>> getLegalProps() {
		return legalProps;
	}

	public Map<Integer, GdlTerm> getLegalToGdlMap() {
		return legalToGdlMap;
	}

	public boolean getProp(int propIndex) {
		return network[propIndex];
	}

	public List<Role> getRoles() {
		return roles;
	}

	public int getTermProposition() {
		return termProposition;
	}

	private Map<Integer,Integer> makeLegalInputMap(PropNet p) {
		HashMap<Integer, Integer> legalInputMap = new HashMap<Integer,Integer>();
		inputPropMap = new HashMap<Role, Map<GdlTerm,Integer>>();

		Map<Proposition,Proposition> pLegals = p.getLegalInputMap();

		for (Map.Entry<Role, Set<Proposition>> e : p.getLegalPropositions().entrySet()) {
			Iterator<Proposition> iter = e.getValue().iterator();
			Map<GdlTerm,Integer> propMap = new HashMap<GdlTerm,Integer>();

			while(iter.hasNext()) {
				Proposition prop = iter.next();
				int idxL = indexMap.get(prop);
				int idxI = indexMap.get(pLegals.get(prop));

				legalInputMap.put(idxL,idxI);
				propMap.put(legalToGdlMap.get(idxL), idxI);
			}

			inputPropMap.put(e.getKey(), propMap);
		}


		return legalInputMap;
	}

	private List<Integer> recordBaseProps(PropNet p) {

		List<Integer> props = new ArrayList<Integer>();

		for (Map.Entry<GdlSentence, Proposition> e : p.getBasePropositions().entrySet()) {
			props.add(indexMap.get(e.getValue()));
		}

		return props;
	}

	private Map<Role,List<Integer>> recordGoalPropositions(PropNet p) {
		Map<Role,Set<Proposition>> pGoals = p.getGoalPropositions();
		HashMap<Role,List<Integer>> goals = new HashMap<Role,List<Integer>>();

		for (Map.Entry<Role, Set<Proposition>> e : pGoals.entrySet()) {
			ArrayList<Integer> props = new ArrayList<Integer>(e.getValue().size());
			Iterator<Proposition> iter = e.getValue().iterator();

			while(iter.hasNext()) {
				props.add(indexMap.get(iter.next()));
			}

			goals.put(e.getKey(), props);
		}

		return goals;
	}

	private Map<Integer,Integer> recordGoalValues(PropNet p) {
		Map<Role,Set<Proposition>> pGoals = p.getGoalPropositions();
		HashMap<Integer,Integer> goals = new HashMap<Integer,Integer>();

		for (Map.Entry<Role, Set<Proposition>> e : pGoals.entrySet()) {
			Iterator<Proposition> iter = e.getValue().iterator();

			while(iter.hasNext()) {
				Proposition prop = iter.next();
				goals.put(indexMap.get(prop),getGoalValue(prop));
			}

		}

		return goals;
	}

	private Map<Role,Set<Integer>> recordInhibitors(PropNet p) {
		Map<Role,Set<Integer>> inhibitors = new HashMap<Role,Set<Integer>>();

		for (Map.Entry<Role, Set<Proposition>> e : p.getInhibitors().entrySet()) {
			Set<Integer> roleInhibitors = new HashSet<Integer>();

			for (Proposition prop : e.getValue()) {
				roleInhibitors.add(indexMap.get(prop));
			}

			inhibitors.put(e.getKey(), roleInhibitors);
		}

		return inhibitors;
	}

	private List<Integer> recordInputProps(PropNet p) {

		List<Integer> props = new ArrayList<Integer>();

		for (Map.Entry<GdlSentence, Proposition> e : p.getInputPropositions().entrySet()) {
			props.add(indexMap.get(e.getValue()));
		}

		return props;
	}

	private Set<Integer> recordLatches(PropNet p) {
		Set<Integer> latches = new HashSet<Integer>();
		for (Proposition prop : p.getLatches()) {
			latches.add(indexMap.get(prop));
		}

		return latches;
	}

	private Map<Role, List<Integer>> recordLegalInputs(PropNet p) {
		Map<Role,Set<Proposition>> pLegals = p.getLegalPropositions();

		Map<Role, List<Integer>> legalInputs = new HashMap<Role,List<Integer>>();
		legalToGdlMap = new HashMap<Integer,GdlTerm>();

		for (Map.Entry<Role, Set<Proposition>> e : pLegals.entrySet()) {
			List<Integer> legals = new ArrayList<Integer>(e.getValue().size());

			Iterator<Proposition> iter = e.getValue().iterator();

			while(iter.hasNext()) {
				Proposition prop = iter.next();
				int index = indexMap.get(prop);
				legalToGdlMap.put(index, getGdlTermFromProposition(prop));
				legals.add(index);
			}

			legalInputs.put(e.getKey(), legals);
		}

		return legalInputs;
	}

	public void clearInputProps() {
		Arrays.fill(network, numBases, numBases+numInputs, false);
	}

	public void setBaseProps(boolean [] bases) {
		System.arraycopy(bases, 0, network, 0, bases.length);
	}

	public void setTrue(int propIndex) {
		network[propIndex] = true;
	}

	public void setFalse(int propIndex) {
		network[propIndex] = false;
	}

	public abstract void update();

	public abstract void updateBases();

	public abstract void updateSingleProp(int propId);

	public boolean [] getUpdatedBases() {
		boolean [] originalBases = getBaseProps();
		updateBases();
		boolean [] newBases = getBaseProps();
		setBaseProps(originalBases);
		return newBases;
	}

	public int getNumProps() {
		return network.length;
	}
}
