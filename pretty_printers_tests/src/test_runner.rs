// Inspired by https://github.com/rust-lang/rust/blob/master/src/tools/compiletest/src/runtest.rs
extern crate toml;

use std::ffi::{OsStr, OsString};
use std::fs::File;
use std::io::BufRead;
use std::io::BufReader;
use std::io::Write;
use std::path::Path;
use std::process::Command;
use std::process::ExitStatus;
use std::process::Output;

static ENABLE_RUST: &'static str = "type category enable Rust";
static BINARY: &'static str = "testbinary";

#[derive(Clone)]
pub enum Debugger { LLDB, GDB }

#[derive(Clone)]
pub struct LLDBConfig {
    pub test_dir: String,
    pub pretty_printers_path: String,
    pub lldb_batchmode: String,
    pub lldb_lookup: String,
    pub lldb_python: String,
    pub python: String,
    pub print_stdout: bool,
    pub native_rust: bool,
}

#[derive(Clone)]
pub struct GDBConfig {
    pub test_dir: String,
    pub pretty_printers_path: String,
    pub gdb: String,
    pub gdb_lookup: String,
    pub print_stdout: bool,
    pub native_rust: bool,
}

#[derive(Clone)]
pub enum Config { LLDB(LLDBConfig), GDB(GDBConfig) }

struct DebuggerCommands {
    commands: Vec<String>,
    check_lines: Vec<String>,
    breakpoint_lines: Vec<usize>,
}

pub trait TestRunner<'test> {
    fn run(&self) -> Result<(), String>;
}

pub fn create_test_runner<'test>(
    config: &'test Config,
    src_path: &'test Path,
) -> Box<TestRunner<'test> + 'test> {
    match config {
        Config::LLDB(config) => Box::new(LLDBTestRunner { config, src_path }),
        Config::GDB(config) => Box::new(GDBTestRunner { config, src_path })
    }
}

pub struct LLDBTestRunner<'test> {
    pub config: &'test LLDBConfig,
    pub src_path: &'test Path,
}

pub struct GDBTestRunner<'test> {
    pub config: &'test GDBConfig,
    pub src_path: &'test Path,
}

struct ProcessResult {
    status: ExitStatus,
    stdout: String,
    stderr: String,
}

impl ProcessResult {
    fn from(out: &Output) -> ProcessResult {
        ProcessResult {
            status: out.status,
            stdout: String::from_utf8_lossy(&out.stdout).into_owned(),
            stderr: String::from_utf8_lossy(&out.stderr).into_owned(),
        }
    }
}

impl<'test> GDBTestRunner<'test> {
    fn run_gdb(
        &self,
        test_executable: &Path,
        debugger_opts: &[&OsStr],
    ) -> ProcessResult {
        let out = Command::new(&self.config.gdb)
            .arg(test_executable)
            .args(debugger_opts).output()
            .unwrap();

        ProcessResult::from(&out)
    }
}

impl<'test> TestRunner<'test> for GDBTestRunner<'test> {
    fn run(&self) -> Result<(), String> {
        let compile_result = compile_test(self.src_path);
        if !compile_result.status.success() {
            return Err(String::from("Compilation failed!"));
        }

        let prefixes = if self.config.native_rust {
            static PREFIXES: &'static [&'static str] = &["gdb", "gdbr"];
            PREFIXES
        } else {
            static PREFIXES: &'static [&'static str] = &["gdb", "gdbg"];
            PREFIXES
        };

        // Parse debugger commands etc from test files
        let DebuggerCommands {
            commands,
            check_lines,
            breakpoint_lines,
            ..
        } = parse_debugger_commands(self.src_path, prefixes)?;

        let exe_file = Path::new("./").join(BINARY);

        let mut script_str = String::with_capacity(2048);
        // script_str.push_str(&format!("set charset {}\n", charset));
        script_str.push_str("show version\n");

        // The following line actually doesn't have to do anything with
        // pretty printing, it just tells GDB to print values on one line:
        script_str.push_str("set print pretty off\n");

        // Add the pretty printer directory to GDB's source-file search path
        script_str.push_str(&format!("directory {}\n", self.config.pretty_printers_path));

        script_str.push_str(&format!("python sys.path.insert(0, \"{}\")\n", self.config.pretty_printers_path));
        script_str.push_str(&format!("python import {}\n", self.config.gdb_lookup));
        script_str.push_str(&format!("python {}.register_printers(gdb)\n", self.config.gdb_lookup));

        // Load the target executable
        script_str.push_str(&format!("file {}\n", exe_file.to_str().unwrap()));

        // Force GDB to print values in the Rust format.
        if self.config.native_rust {
            script_str.push_str("set language rust\n");
        }

        // Add line breakpoints
        let source_file_name = self.src_path.file_name().unwrap().to_string_lossy();
        for line in &breakpoint_lines {
            script_str.push_str(&format!("break '{}':{}\n", source_file_name, line));
        }

        for line in &commands {
            script_str.push_str(line);
            script_str.push_str("\n");
        }
        script_str.push_str("\nquit\n");

        dump_output_file(&script_str, "debugger.script");

        let debugger_script = OsString::from("-command=debugger.script");
        let debugger_opts: &[&OsStr] = &[
            "-quiet".as_ref(),
            "-batch".as_ref(),
            "-nx".as_ref(),
            &debugger_script,
        ];

        let debugger_run_result = self.run_gdb(&exe_file, &debugger_opts);

        if !debugger_run_result.status.success() {
            return Err(String::from(format!("Error while running GDB:\n{}", debugger_run_result.stderr)));
        }

        check_debugger_output(&debugger_run_result, &check_lines, self.config.print_stdout)
    }
}

impl<'test> LLDBTestRunner<'test> {
    fn run_lldb(
        &self,
        test_executable: &Path,
        debugger_script: &Path,
        lldb_batchmode: &Path,
    ) -> ProcessResult {
        // Prepare the lldb_batchmode which executes the debugger script
        let out = Command::new(&self.config.python)
            .arg(lldb_batchmode)
            .arg(test_executable)
            .arg(debugger_script)
            .env("PYTHONPATH", &self.config.lldb_python)
            .output()
            .unwrap();

        ProcessResult::from(&out)
    }
}

impl<'test> TestRunner<'test> for LLDBTestRunner<'test> {
    fn run(&self) -> Result<(), String> {
        let compile_result = compile_test(self.src_path);
        if !compile_result.status.success() {
            return Err(String::from("Compilation failed!"));
        }

        let prefixes = if self.config.native_rust {
            static PREFIXES: &'static [&'static str] = &["lldb", "lldbr"];
            PREFIXES
        } else {
            static PREFIXES: &'static [&'static str] = &["lldb", "lldbg"];
            PREFIXES
        };

        // Parse debugger commands etc from test files
        let DebuggerCommands {
            commands,
            check_lines,
            breakpoint_lines,
            ..
        } = parse_debugger_commands(self.src_path, prefixes)?;

        // Write debugger script:
        // We don't want to hang when calling `quit` while the process is still running
        let mut script_str = String::from("settings set auto-confirm true\n");

        script_str.push_str(&format!("command script import {}{}.py\n", &self.config.pretty_printers_path, &self.config.lldb_lookup));
        script_str.push_str(&format!("type synthetic add -l {}.synthetic_lookup -x '.*' --category Rust\n", &self.config.lldb_lookup));
        script_str.push_str(&format!("type summary add -F {}.summary_lookup -e -x -h '.*' --category Rust\n", &self.config.lldb_lookup));
        script_str.push_str(&format!("{}\n", ENABLE_RUST));

        // Set breakpoints on every line that contains the string "#break"
        let source_file_name = self.src_path.file_name().unwrap().to_string_lossy();
        for line in &breakpoint_lines {
            script_str.push_str(&format!(
                "breakpoint set --file '{}' --line {}\n",
                source_file_name, line
            ));
        }

        // Append the other commands
        for line in &commands {
            script_str.push_str(line);
            script_str.push_str("\n");
        }

        // Finally, quit the debugger
        script_str.push_str("\nquit\n");

        // Write the script into a file
        dump_output_file(&script_str, "debugger.script");
        let debugger_script = Path::new("debugger.script");
        let lldb_batchmode_path = Path::new(&self.config.lldb_batchmode);
        let exe_file = Path::new("./").join(BINARY);

        // Let LLDB execute the script via lldb_batchmode.py
        let debugger_run_result = self.run_lldb(&exe_file, &debugger_script, &lldb_batchmode_path);

        if !debugger_run_result.status.success() {
            return Err(String::from(format!("Error while running LLDB:\n{}", debugger_run_result.stderr)));
        }

        check_debugger_output(&debugger_run_result, &check_lines, self.config.print_stdout)
    }
}

fn parse_debugger_commands(src_path: &Path, debugger_prefixes: &[&str]) -> Result<DebuggerCommands, String> {
    let directives = debugger_prefixes
        .iter()
        .map(|prefix| (format!("{}-command", prefix), format!("{}-check", prefix)))
        .collect::<Vec<_>>();

    let mut breakpoint_lines = vec![];
    let mut commands = vec![];
    let mut check_lines = vec![];
    let mut counter = 1;
    let reader = BufReader::new(File::open(src_path).unwrap());
    for line in reader.lines() {
        match line {
            Ok(line) => {
                let line = if line.starts_with("//") {
                    line[2..].trim_left()
                } else {
                    line.as_str()
                };

                if line.contains("#break") {
                    breakpoint_lines.push(counter);
                }

                for &(ref command_directive, ref check_directive) in &directives {
                    parse_name_value_directive(&line, command_directive)
                        .map(|cmd| commands.push(cmd));

                    parse_name_value_directive(&line, check_directive)
                        .map(|cmd| check_lines.push(cmd));
                }
            }
            Err(e) => {
                return Err(format!("Error while parsing debugger commands: {}", e));
            }
        }
        counter += 1;
    }

    Ok(DebuggerCommands { commands, check_lines, breakpoint_lines })
}

pub fn parse_name_value_directive(line: &str, directive: &str) -> Option<String> {
    let colon = directive.len();
    if line.starts_with(directive) && line.as_bytes().get(colon) == Some(&b':') {
        let value = line[(colon + 1)..].to_owned();
        Some(value)
    } else {
        None
    }
}

fn dump_output_file(out: &str, extension: &str) {
    File::create(&extension)
        .unwrap()
        .write_all(out.as_bytes())
        .unwrap();
}

fn compile_test(path: &Path) -> ProcessResult {
    let out = Command::new("rustc")
        .arg("--crate-type")
        .arg("bin")
        .arg("-o")
        .arg(BINARY)
        .arg("-g")
        .arg(path)
        .output()
        .unwrap();

    ProcessResult::from(&out)
}

fn check_debugger_output(
    debugger_result: &ProcessResult,
    check_lines: &[String],
    print_stdout: bool,
) -> Result<(), String> {
    fn check_single_line(line: &str, check_line: &str) -> bool {
        // Allow check lines to leave parts unspecified (e.g., uninitialized
        // bits in the  wrong case of an enum) with the notation "[...]".
        let line = line.trim();
        let check_line = check_line.trim();
        let can_start_anywhere = check_line.starts_with("[...]");
        let can_end_anywhere = check_line.ends_with("[...]");

        let check_fragments: Vec<&str> = check_line
            .split("[...]")
            .filter(|frag| !frag.is_empty())
            .collect();
        if check_fragments.is_empty() {
            return true;
        }

        let (mut rest, first_fragment) = if can_start_anywhere {
            match line.find(check_fragments[0]) {
                Some(pos) => (&line[pos + check_fragments[0].len()..], 1),
                None => return false,
            }
        } else {
            (line, 0)
        };

        for current_fragment in &check_fragments[first_fragment..] {
            match rest.find(current_fragment) {
                Some(pos) => {
                    rest = &rest[pos + current_fragment.len()..];
                }
                None => return false,
            }
        }

        if !can_end_anywhere && !rest.is_empty() {
            return false;
        }

        true
    }

    let num_check_lines = check_lines.len();

    let mut check_line_index = 0;
    let mut started = false;
    let mut output_lines = Vec::new();

    for line in debugger_result.stdout.lines() {
        if started {
            output_lines.push(line);

            if check_line_index >= num_check_lines {
                break;
            }

            if check_single_line(line, &(check_lines[check_line_index])[..]) {
                check_line_index += 1;
            }
        }

        started = started || line.contains(ENABLE_RUST) || line.contains("Breakpoint");
    }

    if check_line_index != num_check_lines && num_check_lines > 0 {
        let mut result = String::new();
        if print_stdout {
            result.push_str("---------------- stdout ----------------\n");
            result.push_str(&output_lines.join("\n").trim());
            result.push_str("\n---------------------------------------------");
        }
        Err(format!("{}\nNot found: {}", result, check_lines[check_line_index]))
    } else {
        Ok(())
    }
}
