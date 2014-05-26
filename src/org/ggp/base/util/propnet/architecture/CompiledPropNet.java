package org.ggp.base.util.propnet.architecture;

import java.util.Map;

import org.ggp.base.util.propnet.architecture.components.Proposition;

public abstract class CompiledPropNet {

	protected byte[] network;
	protected Map<Proposition, Integer> indexMap;

	public CompiledPropNet(int size, Map<Proposition, Integer> indexMap) {
		network = new byte[size];
		this.indexMap = indexMap;
	}

	public abstract void update();
}
