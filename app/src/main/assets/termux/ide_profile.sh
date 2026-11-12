# AndroidIDE shell profile - managed by the IDE, rewritten at every terminal start.
# Put your own settings in ~/.bashrc (it is read before this file; to opt out of the
# prompt below, run: touch ~/.no_ide_prompt).
[ -n "$PS1" ] || return 0

# --- history & shell options ---------------------------------------------------------
export HISTSIZE=5000 HISTFILESIZE=10000 HISTCONTROL=ignoreboth:erasedups
shopt -s histappend checkwinsize cdspell 2>/dev/null
export CLICOLOR=1

# --- aliases -------------------------------------------------------------------------
alias ls='ls --color=auto'
alias ll='ls -lah'
alias la='ls -A'
alias grep='grep --color=auto'
alias ..='cd ..'
alias ...='cd ../..'
alias gs='git status -sb'
alias gl='git log --oneline --graph -15'
alias gw='./gradlew --console=plain'
alias gwd='./gradlew --console=plain assembleDebug'
alias gwr='./gradlew --console=plain assembleRelease'
alias gwc='./gradlew --console=plain clean'

# --- helpers -------------------------------------------------------------------------
# proj            -> ~/AndroidIDEProjects        proj MyApp -> that project
proj() { cd "$HOME/AndroidIDEProjects/$1" 2>/dev/null || cd "$HOME/AndroidIDEProjects"; }

# ide-info        -> toolchain and storage summary
ide-info() {
  echo "Java     : $(java -version 2>&1 | head -1)"
  echo "SDK      : ${ANDROID_HOME:-not set}"
  echo "Gradle   : ${GRADLE_USER_HOME:-$HOME/.gradle}"
  echo "Projects : $HOME/AndroidIDEProjects"
  echo "Free     : $(df -h "$HOME" 2>/dev/null | awk 'NR==2{print $4" free of "$2}')"
}

# --- prompt: folder, git branch, red arrow after a failed command ----------------------
__ide_git() {
  local b
  b=$(git symbolic-ref --short -q HEAD 2>/dev/null) && printf ' (%s)' "$b"
}
if [ ! -e "$HOME/.no_ide_prompt" ]; then
  __ide_prompt() {
    local rc=$?
    local mark='\[\e[1;32m\]>\[\e[0m\]'
    [ "$rc" -ne 0 ] && mark='\[\e[1;31m\]>\[\e[0m\]'
    PS1='\[\e[1;34m\]\w\[\e[0m\]\[\e[0;33m\]$(__ide_git)\[\e[0m\] '"$mark "
  }
  PROMPT_COMMAND=__ide_prompt
fi

# --- one short hint per session -------------------------------------------------------
if [ -z "$IDE_HINT_SHOWN" ]; then
  export IDE_HINT_SHOWN=1
  printf '\e[2mproj [name] - open projects | gwd - assembleDebug | ide-info - toolchain\e[0m\n'
fi
