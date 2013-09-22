package ca.mcgill.mcb.pcingola.bigDataScript.lang.nativeMethods.string;

import ca.mcgill.mcb.pcingola.bigDataScript.exec.Task;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.Parameters;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.Type;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.nativeMethods.MethodNative;
import ca.mcgill.mcb.pcingola.bigDataScript.run.BigDataScriptThread;

public class MethodNative_string_exitCode extends MethodNative {
	public MethodNative_string_exitCode() {
		super();
	}

	@Override
	protected void initMethod() {
		functionName = "exitCode";
		classType = Type.STRING;
		returnType = Type.INT;

		String argNames[] = { "this" };
		Type argTypes[] = { Type.STRING };
		parameters = Parameters.get(argTypes, argNames);
		addNativeMethodToScope();
	}

	@Override
	protected Object runMethodNative(BigDataScriptThread csThread, Object objThis) {
		String taskId = objThis.toString(); Task task = csThread.getTask(taskId); if (task == null) return 0; return (long) task.getExitValue();
	}
}