package ca.mcgill.mcb.pcingola.bigDataScript.run;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.mcgill.mcb.pcingola.bigDataScript.lang.ExpressionDepOperator;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.ExpressionTask;
import ca.mcgill.mcb.pcingola.bigDataScript.task.Task;
import ca.mcgill.mcb.pcingola.bigDataScript.util.AutoHashMap;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Gpr;

/**
 * Store task and dependency graph
 *
 * @author pcingola
 */
public class TaskDependecies {

	boolean debug = false;
	List<Task> tasks; // Sorted list of tasks (need it for serialization purposes)
	Map<String, Task> tasksById;
	AutoHashMap<String, List<Task>> tasksByOutput;
	BigDataScriptThread bdsThread;

	public TaskDependecies(BigDataScriptThread bdsThread) {
		tasksByOutput = new AutoHashMap<String, List<Task>>(new LinkedList<Task>());
		tasksById = new HashMap<String, Task>();
		tasks = new ArrayList<Task>();
		this.bdsThread = bdsThread;
	}

	/**
	 * Add a task
	 */
	public synchronized void add(Task task) {
		if (isCircular(task)) throw new RuntimeException("Circular dependency on task '" + task.getId() + "'");
		addTask(task);
		if (!task.isDependency()) findDirectDependencies(task); // Find and update task's immediate dependencies (only if the task is to be executed)
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

	public Task get(String taskId) {
		return tasksById.get(taskId);
	}

	public Collection<String> getTaskIds() {
		return tasksById.keySet();
	}

	public Collection<Task> getTasks() {
		return tasks;
	}

	/**
	 * Find tasks required to achieve goal 'out'
	 */
	public Set<Task> goal(String out) {
		Set<Task> tasks = new HashSet<>();
		goalRun(out, tasks);
		return tasks;
	}

	/**
	 * Does this goal need to be updated respect to the leaves
	 */
	boolean goalNeedsUpdate(String out) {
		// Find all 'leaf nodes' (files) required for this goal
		Set<String> leaves = findLeafNodes(out);

		if (debug) {
			Gpr.debug("\n\tGoal: " + out + "\n\tLeaf nodes:");
			for (String n : leaves)
				System.err.println("\t\t'" + n + "'");
		}

		return needsUpdate(out, leaves);
	}

	/**
	 * Find all leaf nodes required for goal 'out'
	 */
	boolean goalRun(String goal, Set<Task> addedTasks) {

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
					goalRun(in, addedTasks);
		}

		// Run all tasks
		for (Task t : tasks) {
			t.setDependency(false); // We are executing this task, so it it no long a 'dep'
			ExpressionTask.execute(bdsThread, t);
		}

		return true;
	}

	public boolean hasTask(String taskId) {
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
	 * Do 'outs' files need to be updated respect to 'ins'?
	 */
	boolean needsUpdate(Collection<String> outs, Collection<String> ins) {
		ExpressionDepOperator dep = new ExpressionDepOperator(null, null);
		return dep.depOperator(outs, ins, debug);
	}

	/**
	 * Does 'out' file need to be updated respect to 'ins'?
	 */
	boolean needsUpdate(String out, Collection<String> ins) {
		// Collection of output files (only one element)
		LinkedList<String> outs = new LinkedList<>();
		outs.add(out);
		return needsUpdate(outs, ins);
	}

	/**
	 * Do output files in this task need to be updated?
	 */
	boolean needsUpdate(Task task) {
		Collection<String> outs = task.getOutputFiles() != null ? task.getOutputFiles() : new LinkedList<String>();
		Collection<String> ins = task.getInputFiles() != null ? task.getInputFiles() : new LinkedList<String>();
		return needsUpdate(outs, ins);
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

}