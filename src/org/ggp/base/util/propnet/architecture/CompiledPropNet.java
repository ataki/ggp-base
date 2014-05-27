package org.ggp.base.util.propnet.architecture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
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

	protected byte[] network;
	protected final Map<Proposition, Integer> indexMap;

	protected final List<Integer> baseProps;
	protected final int initProposition;

	public int getInitProposition() {
		return initProposition;
	}

	public int getTermProposition() {
		return termProposition;
	}

	protected final int termProposition;
	protected final List<Integer> inputProps;

	protected final Map<Role, List<Integer>> legalProps;

	public Map<Role, List<Integer>> getLegalProps() {
		return legalProps;
	}

	public Map<Integer, Integer> getLegalInputMap() {
		return legalInputMap;
	}

	protected final Map<Integer, Integer> legalInputMap;

	protected final List<Role> roles;

	protected final Map<Role, List<Integer>> goalPropositions;

	public Map<Role, List<Integer>> getGoalPropositions() {
		return goalPropositions;
	}

	public Map<Integer, Integer> getGoalValues() {
		return goalValues;
	}

	protected final Map<Integer, Integer> goalValues;

	public List<Role> getRoles() {
		return roles;
	}

	protected final int numBases;
	protected final int numInputs;

	protected Map<Role,Map<GdlTerm,Integer>> inputPropMap;
	protected Map<Integer, GdlTerm> legalToGdlMap;

	public Map<Role, Map<GdlTerm, Integer>> getInputPropMap() {
		return inputPropMap;
	}

	public Map<Integer, GdlTerm> getLegalToGdlMap() {
		return legalToGdlMap;
	}

	public CompiledPropNet(int size, Map<Proposition, Integer> indexMap, PropNet p) {
		network = new byte[size];
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

	private List<Integer> recordBaseProps(PropNet p) {

		List<Integer> props = new ArrayList<Integer>();

		for (Map.Entry<GdlSentence, Proposition> e : p.getBasePropositions().entrySet()) {
			props.add(indexMap.get(e.getValue()));
		}

		return props;
	}

	private List<Integer> recordInputProps(PropNet p) {

		List<Integer> props = new ArrayList<Integer>();

		for (Map.Entry<GdlSentence, Proposition> e : p.getInputPropositions().entrySet()) {
			props.add(indexMap.get(e.getValue()));
		}

		return props;
	}

	/**
	 * Takes in a Legal Proposition and returns the appropriate corresponding Move
	 * @param p
	 * @return a PropNetMove
	 */
	public static GdlTerm getGdlTermFromProposition(Proposition p)
	{
		return p.getName().get(1);
	}

	public void setTrue(int propIndex) {
		network[propIndex] = (byte)1;
	}

	public byte getProp(int propIndex) {
		return network[propIndex];
	}

	public void clear() {
		network = new byte[network.length];
	}

	public byte [] getBaseProps() {
		return Arrays.copyOfRange(network, 0, baseProps.size());
	}

	public void setBaseProps(byte [] bases) {
		System.arraycopy(bases, 0, network, 0, bases.length);
	}

	public abstract void update();

	public abstract void updateBases();
}
