package com.github.alexcojocaru.mojo.elasticsearch.v2.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ProcessDestroyer;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.exec.environment.EnvironmentUtils;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.plugin.logging.Log;

import com.github.alexcojocaru.mojo.elasticsearch.v2.ElasticsearchSetupException;
import com.github.alexcojocaru.mojo.elasticsearch.v2.InstanceConfiguration;

/**
 * @author Alex Cojocaru
 */
public class ProcessUtil
{
    /**
     * Build an OS dependent command line around the given executable name / relative path.
     * On Windows, the '/' file path separator in the executable are replaced with '\'.
     * @param executable - the executable name or relative path
     * @return - the command line tailored to the current OS
     */
    public static CommandLine buildCommandLine(String executable)
    {
        CommandLine cmd;

        if (SystemUtils.IS_OS_WINDOWS)
        {
            String windowsExecutable = executable.replace('/', '\\');
            cmd = new CommandLine("cmd")
                    .addArgument("/c")
                    .addArgument(windowsExecutable);
        }
        else
        {
            cmd = new CommandLine(executable);
        }
        
        return cmd;
    }
    
    /**
     * Build an OS dependent command line to kill the process with the given PID.
     */
    public static CommandLine buildKillCommandLine(String pid)
    {
        CommandLine command;

        if (SystemUtils.IS_OS_WINDOWS)
        {
            command = new CommandLine("taskkill")
                    .addArgument("/F")
                    .addArgument("/pid")
                    .addArgument(pid);
        }
        else
        {
            command = new CommandLine("kill").addArgument(pid);
        }
        
        return command;
    }

    /**
     * Read the ES PID from the pid file and return it.
     * @param baseDir the base directory where the pid file is
     * @return
     */
    public static String getElasticsearchPid(String baseDir)
    {
        try
        {
            String pid = new String(Files.readAllBytes(Paths.get(baseDir, "pid")));
            return pid;
        }
        catch (IOException e)
        {
            throw new IllegalStateException(
                    String.format(
                            "Cannot read the PID of the Elasticsearch process from the pid file in directory '%s'",
                            baseDir),
                    e);
        }
    }

    /**
     * Check if the process with the given PID is running or not.
     * This method only handles processes running on Windows.
     * @param config - the instance config
     * @param pid the process ID
     * @return true if the process is running, false otherwise
     */
    public static boolean isWindowsProcessAlive(InstanceConfiguration config, String pid)
    {
        CommandLine command = new CommandLine("tasklist")
                .addArgument("/FI")
                .addArgument("PID eq " + pid, true);
        List<String> output = executeScript(config, command, true);
        
        String keyword = String.format(" %s ", pid);
        boolean isRunning = output.stream().anyMatch(s -> s.contains(keyword));

        return isRunning;
    }
    
    /**
     * Run the given command as a process within the supplied instance config context
     * and wait until it finalizes. An ElasticsearchSetupException is thrown if the exit code
     * is not 0.
     * @param config - the instance config
     * @param command - the command to execute
     * @return the output (as separate lines)
     */
    public static List<String> executeScript(InstanceConfiguration config, CommandLine command)
    {
        return executeScript(config, command, null, null, false);
    }
    
    /**
     * Run the given command as a process within the supplied instance config context
     * and wait until it finalizes. An ElasticsearchSetupException is thrown if the exit code
     * is not 0.
     * @param config - the instance config
     * @param command - the command to execute
     * @param disableLogging - whether to disable the logging of the command or not
     * @return the output (as separate lines)
     */
    public static List<String> executeScript(
            InstanceConfiguration config,
            CommandLine command,
            boolean disableLogging)
    {
        return executeScript(config, command, null, null, disableLogging);
    }
    
    /**
     * Run the given command as a process within the supplied instance config context
     * and wait until it finalizes. An ElasticsearchSetupException is thrown if the exit code
     * is not 0.
     * @param config - the instance config
     * @param command - the command to execute
     * @param environment - a map of environment variables; can be null
     * @param processDestroyer - a destroyer handler for the spawned process; can be null 
     * @return the output (not trimmed of whitespaces) of the given command, as separate lines
     */
    public static List<String> executeScript(InstanceConfiguration config,
            CommandLine command,
            Map<String, String> environment,
            ProcessDestroyer processDestroyer)
    {
        return executeScript(config, command, environment, processDestroyer, false);
    }
    
    /**
     * Run the given command as a process within the supplied instance config context
     * and wait until it finalizes. An ElasticsearchSetupException is thrown if the exit code
     * is not 0.
     * @param config - the instance config
     * @param command - the command to execute
     * @param environment - a map of environment variables; can be null
     * @param processDestroyer - a destroyer handler for the spawned process; can be null 
     * @param disableLogging - whether to disable the logging of the command or not
     * @return the output (not trimmed of whitespaces) of the given command, as separate lines
     */
    public static List<String> executeScript(InstanceConfiguration config,
            CommandLine command,
            Map<String, String> environment,
            ProcessDestroyer processDestroyer,
            boolean disableLogging)
    {
        Log log = config.getClusterConfiguration().getLog();
        int instanceId = config.getId();
        File baseDir = new File(config.getBaseDir()); 
        
        Map<String, String> completeEnvironment = createEnvironment(environment);

        DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory(baseDir);
        executor.setProcessDestroyer(processDestroyer); // allows null
        
        // set up a tap on the output stream, to collect to output and return it from this method
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        executor.setStreamHandler(new PumpStreamHandler(
                disableLogging ? outputStream : new TeeOutputStream(System.out, outputStream),
                disableLogging ? errorStream : new TeeOutputStream(System.err, errorStream)));

        try
        {
            log.debug(String.format("Using environment: %s", completeEnvironment));
            
            String commandMessage = String.format(
                    "Elasticsearch[%d]: Executing command '%s' in directory '%s'",
                    instanceId,
                    command.toString(),
                    baseDir);
            if (disableLogging)
            {
                log.debug(commandMessage);
            }
            else
            {
                log.info(commandMessage);
            }

            int exitCode = executor.execute(command, completeEnvironment);
            
            if (exitCode != 0)
            {
                throw new ElasticsearchSetupException(String.format(
                        "Elasticsearch [%d]: Command '%s' in directory '%s' finished with exit code %d; see above for details",
                        instanceId, command, baseDir, exitCode));
            }
            
            String resultMessage = String.format(
                    "Elasticsearch[%d]: The process finished with exit code %d",
                    instanceId,
                    exitCode);
            if (disableLogging)
            {
                log.debug(resultMessage);
            }
            else
            {
                log.info(resultMessage);
            }
        }
        catch (IOException e)
        {
            List<String> output = readBuffer(outputStream);
            List<String> error = readBuffer(errorStream);
            
            String lineSeparator = System.getProperty("line.separator");
            StringBuilder message = new StringBuilder();
            message.append("Elasticsearch [");
            message.append(instanceId);
            message.append("]: Cannot execute command '");
            message.append(command);
            message.append("' in directory '");
            message.append(baseDir);
            message.append("'");
            message.append(lineSeparator);
            message.append("Output:");
            message.append(lineSeparator);
            message.append(StringUtils.join(output, lineSeparator));
            message.append(lineSeparator);
            message.append("Error:");
            message.append(lineSeparator);
            message.append(StringUtils.join(error, lineSeparator));

            throw new ElasticsearchSetupException(message.toString(), e);
        }
        
        return readBuffer(outputStream);
    }
    
    private static List<String> readBuffer(ByteArrayOutputStream outputStream)
    {
        List<String> outputLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(outputStream.toString())))
        {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
            }
        }
        catch (IOException ex) {
            // not much to do here
        }
        
        return Collections.unmodifiableList(outputLines);
    }

    /**
     * Create an environment by merging the current environment and the supplied one.
     * If the supplied environment is null, null is returned.
     * @param environment
     * @return an execution environment
     */
    private static Map<String, String> createEnvironment(Map<String, String> environment)
    {
        Map<String, String> result = null;
        
        if (environment != null)
        {
            try
            {
                result = EnvironmentUtils.getProcEnvironment();
            }
            catch (IOException ex)
            {
                throw new ElasticsearchSetupException(
                        "Cannot get the current process environment", ex);
            }
            result.putAll(environment);
        }
        
        return result;
    }

}
