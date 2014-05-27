package org.ggp.base.util.propnet.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.architecture.components.Proposition;

public abstract class CompiledPropNet {

	protected final byte[] network;
	protected final Map<Proposition, Integer> indexMap;

	protected final List<Integer> baseProps;
	protected final int initProposition;
	protected final int termProposition;
	//protected final List<Integer> inputProps;

	public CompiledPropNet(int size, Map<Proposition, Integer> indexMap, PropNet p) {
		network = new byte[size];
		this.indexMap = indexMap;

		baseProps = recordBaseProps(p);
		initProposition = indexMap.get(p.getInitProposition());
		termProposition = indexMap.get(p.getTerminalProposition());
	}

	private List<Integer> recordBaseProps(PropNet p) {

		List<Integer> props = new ArrayList<Integer>();

		for (Map.Entry<GdlSentence, Proposition> e : p.getBasePropositions().entrySet()) {
			props.add(indexMap.get(e.getValue()));
		}

		return props;
	}

	public abstract void update();
}
