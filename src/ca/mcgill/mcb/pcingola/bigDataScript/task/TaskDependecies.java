package ca.mcgill.mcb.pcingola.bigDataScript.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.mcgill.mcb.pcingola.bigDataScript.lang.ExpressionTask;
import ca.mcgill.mcb.pcingola.bigDataScript.run.BigDataScriptThread;
import ca.mcgill.mcb.pcingola.bigDataScript.util.AutoHashMap;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Gpr;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Timer;

/**
 * Store task and dependency graph
 *
 * @author pcingola
 */
public class TaskDependecies {

	public static final int SLEEP_TIME = 250;
	private static TaskDependecies taskDependecies = new TaskDependecies(); // Global instance (keeps track of all tasks in this process)

	boolean debug = false;
	boolean verbose = false;
	List<Task> tasks; // Sorted list of tasks (need it for serialization purposes)
	Map<String, Task> tasksById;
	AutoHashMap<String, List<Task>> tasksByOutput;

	public static TaskDependecies get() {
		return taskDependecies;
	}

	public static void reset() {
		taskDependecies = new TaskDependecies();
	}

	public TaskDependecies() {
		tasksByOutput = new AutoHashMap<String, List<Task>>(new LinkedList<Task>());
		tasksById = new HashMap<String, Task>();
		tasks = new ArrayList<Task>();
	}

	/**
	 * Add a task
	 */
	public synchronized void add(Task task) {
		if (isCircular(task)) throw new RuntimeException("Circular dependency on task '" + task.getId() + "'");
		addTask(task);
		if (!task.isDependency()) findDirectDependencies(task); // Find and update task's immediate dependencies (only if the task is to be executed)

		if (this != taskDependecies) taskDependecies.add(task); // Add to glabal object
	}

	/**
	 * Add a task
	 */
	protected synchronized void addTask(Task task) {
		if (tasksById.containsKey(task.getId())) return; // Already added? Nothing to do

		// Add task
		tasksById.put(task.getId(), task);
		tasks.add(task);

		// Add task by output files
		if (task.getOutputFiles() != null) {
			for (String outFile : task.getOutputFiles())
				tasksByOutput.getOrCreate(outFile).add(task);
		}
	}

	/**
	 * Find all tasks that must be completed before we proceed
	 */
	void findDirectDependencies(Task task) {
		if (!task.isDone()) {
			// Add input dependencies based on input files
			if (task.getInputFiles() != null) {
				for (String inFile : task.getInputFiles()) {
					List<Task> taskDeps = tasksByOutput.get(inFile);
					if (taskDeps != null) {
						for (Task taskDep : taskDeps)
							if (!taskDep.isDone() // Don't add finished tasks
									&& !taskDep.isDependency() // If task is a dependency, it may not be executed (because the goal is not triggered). So don't add them
									) task.addDependency(taskDep); // Add it to dependency list
					}
				}
			}
		}
	}

	/**
	 * Find 'leaf' nodes (i.e. nodes that do not have dependent tasks)
	 */
	Set<String> findLeafNodes(String out) {
		Set<String> nodes = findNodes(out); // Find all nodes

		// Only add nodes that do not have dependent tasks (i.e. are leaves)
		Set<String> leaves = new HashSet<String>();
		for (String n : nodes)
			if (!tasksByOutput.containsKey(n)) leaves.add(n);

		return leaves;
	}

	/**
	 * Find all leaf nodes required for goal 'out'
	 */
	Set<String> findNodes(String out) {
		// A set of 'goal' nodes
		Set<String> goals = new HashSet<String>();
		goals.add(out);

		// For each goal
		for (boolean changed = true; changed;) {
			changed = false;

			// We need a new set to avoid 'concurrent modification' exception
			Set<String> newGoals = new HashSet<String>();
			newGoals.addAll(goals);

			// For each goal
			for (String goal : goals) {
				// Find all tasks required for this goal
				List<Task> tasks = tasksByOutput.get(goal);

				if (tasks != null) // Add all task's input files
					for (Task t : tasks)
						if (t.getInputFiles() != null) {
							for (String in : t.getInputFiles())
								changed |= newGoals.add(in); // Add each node
						}
			}

			goals = newGoals;
		}

		return goals;
	}

	public synchronized Task getTask(String taskId) {
		return tasksById.get(taskId);
	}

	public synchronized Collection<String> getTaskIds() {
		return tasksById.keySet();
	}

	public synchronized Collection<Task> getTasks() {
		return tasks;
	}

	/**
	 * Find tasks required to achieve goal 'out'
	 */
	public synchronized Set<Task> goal(BigDataScriptThread bdsThread, String out) {
		Set<Task> tasks = new HashSet<>();
		goalRun(bdsThread, out, tasks);
		return tasks;
	}

	/**
	 * Does this goal need to be updated respect to the leaves
	 */
	boolean goalNeedsUpdate(String out) {
		// Find all 'leaf nodes' (files) required for this goal
		Set<String> leaves = findLeafNodes(out);
		TaskDependency tasDep = new TaskDependency(null);
		tasDep.addOutput(out);
		tasDep.addInput(leaves);

		if (debug) {
			Gpr.debug("\n\tGoal: " + out + "\n\tLeaf nodes:");
			for (String n : leaves)
				System.err.println("\t\t'" + n + "'");
		}

		return tasDep.depOperator();
	}

	/**
	 * Find all leaf nodes required for goal 'out'
	 */
	boolean goalRun(BigDataScriptThread bdsThread, String goal, Set<Task> addedTasks) {

		// Check if we really need to update this goal (with respect to the leaf nodes)
		if (!goalNeedsUpdate(goal)) return false;

		List<Task> tasks = tasksByOutput.get(goal);
		if (tasks == null) return false;

		// Satisfy all goals before running
		for (Task t : tasks) {
			if (addedTasks.contains(t)) throw new RuntimeException("Circular dependency on task '" + t.getId() + "'");
			addedTasks.add(t);

			if (t.getInputFiles() != null) //
				for (String in : t.getInputFiles())
					goalRun(bdsThread, in, addedTasks);
		}

		// Run all tasks
		for (Task t : tasks) {
			t.setDependency(false); // We are executing this task, so it it no long a 'dep'
			ExpressionTask.execute(bdsThread, t);
		}

		return true;
	}

	public synchronized boolean hasTask(String taskId) {
		return tasksById.containsKey(taskId);
	}

	/**
	 * Is there a circular dependency for this task?
	 */
	boolean isCircular(Task task) {
		return isCircular(task, new HashSet<Task>());
	}

	/**
	 * Is there a circular dependency for this task?
	 */
	boolean isCircular(Task task, HashSet<Task> tasks) {
		if (!tasks.add(task)) return true;

		// Get all input files, find corresponding tasks and recurse
		if (task.getInputFiles() != null) {
			for (String in : task.getInputFiles()) {
				List<Task> depTasks = tasksByOutput.get(in);

				if (depTasks != null) {
					for (Task t : depTasks)
						if (isCircular(t, tasks)) return true;
				}
			}
		}
		return false;
	}

	/**
	 * Have all tasks finished executing?
	 */
	public boolean isTasksDone() {
		for (String taskId : taskDependecies.getTaskIds()) {
			if ((taskId == null) || taskId.isEmpty()) continue;

			Task task = getTask(taskId);
			if (task == null) continue;

			if (!task.isDone()) return false;
		}

		return true;

	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	void sleep() {
		try {
			Thread.sleep(SLEEP_TIME);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();

		ArrayList<String> outs = new ArrayList<String>();
		outs.addAll(tasksByOutput.keySet());
		Collections.sort(outs);

		for (String out : outs) {
			sb.append(out + ":\n");
			for (Task t : tasksByOutput.get(out))
				sb.append("\t" + t.getId() + "\n");
		}
		return sb.toString();
	}

	/**
	 * Wait for one task to finish
	 * @return true if task finished OK or it was allowed to fail (i.e. canFail = true)
	 */
	public boolean waitTask(String taskId) {
		if ((taskId == null) || taskId.isEmpty()) return true;

		Task task = getTask(taskId);
		if (task == null) return false; // No task? We are done!

		// Is task a dependency?
		if (task.isDependency() && !task.isScheduled()) {
			if (debug) Timer.showStdErr("Wait: Task '" + task.getId() + "' is dependency and has not been scheduled for execution. Not wating.");
			return true;
		}

		if (verbose) Timer.showStdErr("Wait: Waiting for task to finish: " + task.getId());

		// Wait for task to finish
		while (!task.isDone())
			sleep();

		// Either finished OK or it was allowed to fail
		boolean ok = task.isDoneOk() || task.isCanFail();

		// If task failed, show task information and failure reason.
		if (!ok) {
			// Show error and mark all files to be deleted on exit
			System.err.println("Task failed:\n" + task.toString(true));
			task.deleteOutputFilesOnExit();
		}

		if (verbose) Timer.showStdErr("Wait: Task '" + task.getId() + "' finished.");
		return ok;
	}

	/**
	 * Wait for all tasks to finish
	 * @return true if all tasks finished OK or it were allowed to fail (i.e. canFail = true)
	 */
	public boolean waitTasksAll() {
		// Wait for all tasks to finish
		boolean ok = true;

		if (verbose && !isTasksDone()) Timer.showStdErr("Waiting for all tasks to finish.");

		// Get all taskIds in a new collection (to avoid concurrent modification
		LinkedList<String> tids = new LinkedList<>();
		tids.addAll(getTaskIds());

		// Wait for each task
		for (String tid : tids)
			ok &= waitTask(tid);

		return ok;
	}

}